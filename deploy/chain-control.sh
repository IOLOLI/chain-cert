#!/bin/bash
# 控制 FISCO BCOS 群组（默认 5 个节点：node0-3 共识 + node4 监管观察节点）
#
#   chain-control.sh start     逐个拉起，最后等出块
#   chain-control.sh stop      逐个优雅停止
#   chain-control.sh status   pid 数 + 最新区块高度
#   chain-control.sh wait      只等到出块（CI / systemd 自检用）
#
# 环境变量：CHAIN_HOME（默认 /opt/fisco/nodes/127.0.0.1）、RPC_PORT（默认 8545）
set -uo pipefail

CHAIN_HOME="${CHAIN_HOME:-/opt/fisco/nodes/127.0.0.1}"
RPC_PORT="${RPC_PORT:-8545}"
BIN="$CHAIN_HOME/fisco-bcos"

log() { printf '[chain] %s\n' "$*"; }

node_dirs() {
  # 只认 node 开头且带 config.ini / start.sh 的目录，sdk/ 之类自动跳过
  for d in "$CHAIN_HOME"/node*; do
    [ -f "$d/config.ini" ] && [ -f "$d/start.sh" ] && echo "$d"
  done | sort -V
}

running_pids() {
  # 注意：start.sh 以 "${SHELL_FOLDER}/../fisco-bcos" 启动，进程命令行里带 nodeN/..，
  # 所以按 $BIN 全路径匹配会永远匹配不上（表现为 status 显示 0 个进程）。
  # 用 [f] 包住首字母，避免 pgrep 匹配到调用方自己的命令行。
  pgrep -f '[f]isco-bcos.*-c[[:space:]]*config\.ini' || true
}

wait_block() {
  local timeout="${1:-90}" i=0
  while [ "$i" -lt "$timeout" ]; do
    local h
    h=$(curl -s --noproxy '*' -m 2 -X POST -H 'Content-Type: application/json' \
          --data '{"jsonrpc":"2.0","method":"getBlockNumber","params":[1],"id":1}' \
          "http://127.0.0.1:${RPC_PORT}" 2>/dev/null | sed -n 's/.*"result":"\([^"]*\)".*/\1/p')
    if [ -n "$h" ]; then
      log "出块正常，最新区块高度 = $((h))"
      return 0
    fi
    i=$((i + 3)); sleep 3
  done
  log "等待出块超时（${timeout}s）：检查 $CHAIN_HOME/node0/log 下的日志"
  return 1
}

cmd_start() {
  [ -x "$BIN" ] || { log "找不到节点程序 $BIN"; return 1; }
  [ -n "$(running_pids)" ] && { log "已有节点在运行，先 stop"; return 1; }
  for d in $(node_dirs); do
    log "启动 $(basename "$d")"
    ( cd "$d" && bash start.sh >/dev/null 2>&1 ) || { log "$(basename "$d") 启动失败"; return 1; }
    sleep 2
  done
  log "进程数 = $(running_pids | wc -l)"
  wait_block 90
}

cmd_stop() {
  for d in $(node_dirs | tac); do
    [ -f "$d/stop.sh" ] || continue
    log "停止 $(basename "$d")"
    ( cd "$d" && bash stop.sh >/dev/null 2>&1 )
  done
  sleep 2
  local left
  left=$(running_pids | wc -l)
  if [ "$left" -gt 0 ]; then
    log "还有 $left 个进程未退出，强制结束"
    running_pids | xargs -r kill -9
  fi
  log "已停止"
}

cmd_status() {
  local n
  n=$(running_pids | wc -l)
  log "进程数 = $n / $(node_dirs | wc -l)"
  for d in $(node_dirs); do
    printf '  %-6s %s\n' "$(basename "$d")" \
      "$(pgrep -f "$d/../fisco-bcos" >/dev/null && echo running || echo stopped)"
  done
  curl -s --noproxy '*' -m 3 -X POST -H 'Content-Type: application/json' \
    --data '{"jsonrpc":"2.0","method":"getBlockNumber","params":[1],"id":1}' \
    "http://127.0.0.1:${RPC_PORT}" 2>/dev/null || echo "  RPC 无响应"
  echo
}

case "${1:-status}" in
  start)  cmd_start ;;
  stop)   cmd_stop ;;
  status) cmd_status ;;
  wait)   wait_block 90 ;;
  *) echo "用法: $0 {start|stop|status|wait}"; exit 2 ;;
esac
