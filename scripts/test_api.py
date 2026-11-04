#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
chain-cert 后端接口端到端冒烟测试（仅用 Python 标准库，无需 pip install）。

覆盖场景（按业务层级串联成一条完整链路）：
  认证登录 → 链状态/系统统计 → ADMIN 开社保局(北京/上海)与监管账号
  → 社保局开缴费单位 → ADMIN 给公司注资 → 公司开职工账号并建档
  → 调薪 → 缴费 → 职工减员 → 职工发起跨市转移 → 转出局放行(托管)
  → 查在途托管 → 转入局接收 → 核对转移结果 → 监管端大盘/转移详情

用法：
  python test_api.py                       # 默认 http://127.0.0.1:8080
  python test_api.py http://<CHAIN_HOST>:8080

说明：
  - 每次运行用时间戳生成唯一用户名，可重复执行（幂等）。
  - 关键步骤失败会中止后续依赖步骤；末尾输出 PASS/FAIL 汇总，退出码 0/1。
"""
import json
import os
import secrets
import sys
import time
import urllib.request
import urllib.error

BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else os.environ.get("CHAINSIP_BASE_URL", "http://127.0.0.1:8080")
SUFFIX = str(int(time.time()))[-6:]   # 用户名唯一后缀
ADMIN_LOGIN = (os.environ.get("CHAINSIP_ADMIN_USER", "admin"),
              os.environ.get("CHAINSIP_ADMIN_PASSWORD", "ChangeMe_123456"))
TEST_PRIVATE_KEY = secrets.token_hex(32)   # 仅用于 /demo/sign，随机生成，不保存

RESULTS = []


def call(method, path, token=None, body=None, timeout=30):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}
    except Exception as e:
        return 0, {"message": str(e)}


def step(name, method, path, token=None, body=None, critical=True,
         pick=None, expect_ok=True, store=None):
    """执行一步并记录结果。pick 用于从 data 里取值存入 store[key]。"""
    status, resp = call(method, path, token, body)
    ok = status == 200 and (resp.get("ok") is not False if isinstance(resp, dict) else True)
    if expect_ok and ok and pick and store is not None:
        for key, expr in pick.items():
            val = resp.get("data", {})
            for part in expr.split("."):
                if isinstance(val, dict):
                    val = val.get(part)
                elif isinstance(val, list) and part.isdigit():
                    val = val[int(part)] if int(part) < len(val) else None
                else:
                    val = None
                if val is None:
                    break
            store[key] = val
    mark = "PASS" if ok else "FAIL"
    RESULTS.append((name, ok, status, resp if not ok else None))
    color = "\033[32m" if ok else "\033[31m"
    detail = ""
    if not ok and isinstance(resp, dict):
        detail = " -> " + str(resp.get("message", resp))[:160]
    print("%s[%s]%s %s (HTTP %d)%s" % (color, mark, "\033[0m", name, status, detail))
    if critical and not ok:
        print("\n关键步骤失败，中止后续测试。")
        summary()
        sys.exit(1)
    return ok


class Abort(Exception):
    pass


def summary():
    total = len(RESULTS)
    failed = [r for r in RESULTS if not r[1]]
    print("\n" + "=" * 62)
    print("测试汇总: %d 项, 通过 %d, 失败 %d" % (total, total - len(failed), len(failed)))
    for name, ok, status, resp in failed:
        msg = resp.get("message", "") if isinstance(resp, dict) else ""
        print("  [FAIL] %s (HTTP %d) %s" % (name, status, str(msg)[:120]))
    print("=" * 62)
    if failed:
        sys.exit(1)


def main():
    print("目标后端: %s\n" % BASE)

    # ---------- 1. 公开接口（免登录） ----------
    step("健康检查 GET /api/public/health", "GET", "/api/public/health")

    # ---------- 2. ADMIN 登录与只读接口 ----------
    s = {}
    step("ADMIN 登录", "POST", "/api/auth/login",
         body={"username": ADMIN_LOGIN[0], "password": ADMIN_LOGIN[1]},
         pick={"admin_token": "token", "admin_addr": "account.chainAddress"})
    admin = s["admin_token"]
    print("      ADMIN 链上地址: %s" % s.get("admin_addr"))

    # 链状态/统计已纳入登录保护，先登录再访问
    step("链状态 GET /api/chain/status", "GET", "/api/chain/status", token=admin)
    step("链统计 GET /api/chain/stats", "GET", "/api/chain/stats", token=admin)

    step("我的信息 GET /api/auth/me", "GET", "/api/auth/me", token=admin)
    step("平台总览 GET /api/admin/system-stats", "GET", "/api/admin/system-stats", token=admin)
    step("已注册城市 GET /api/admin/cities", "GET", "/api/admin/cities", token=admin)
    step("平台账号列表 GET /api/admin/accounts", "GET", "/api/admin/accounts", token=admin)
    step("机构链账户列表 GET /api/admin/institution-keys", "GET", "/api/admin/institution-keys", token=admin)
    step("联调签名 POST /api/auth/demo/sign", "POST", "/api/auth/demo/sign", token=admin,
         body={"privateKey": TEST_PRIVATE_KEY,
               "message": "smoke-test"})

    # ---------- 3. ADMIN 开两个社保局（自动链上注册并授权） ----------
    bureau_bj = {}
    step("开社保局账号(北京)", "POST", "/api/admin/accounts", token=admin,
         body={"username": "bj_bureau_" + SUFFIX, "password": "ChangeMe_123456", "role": "BUREAU",
               "displayName": "北京市社保局", "name": "北京市社保局", "city": "北京"},
         pick={"bj_login": "account.username", "bj_addr": "chainAddress",
               "bj_ok": "onChainRegistered"})
    print("      北京局地址: %s 链上注册: %s" % (s.get("bj_addr"), s.get("bj_ok")))

    bureau_sh = {}
    step("开社保局账号(上海)", "POST", "/api/admin/accounts", token=admin,
         body={"username": "sh_bureau_" + SUFFIX, "password": "ChangeMe_123456", "role": "BUREAU",
               "displayName": "上海市社保局", "name": "上海市社保局", "city": "上海"},
         pick={"sh_login": "account.username", "sh_addr": "chainAddress"})

    # ---------- 4. ADMIN 开监管账号 ----------
    step("开监管账号", "POST", "/api/admin/accounts", token=admin,
         body={"username": "reg_" + SUFFIX, "password": "ChangeMe_123456", "role": "REGULATOR",
               "displayName": "市银保监监管员", "name": "监管员甲"})

    # ---------- 5. ADMIN 直接开缴费单位（自动链上注册，归属北京） ----------
    sponsor = {}
    step("开缴费单位账号(北京)", "POST", "/api/admin/accounts", token=admin,
         body={"username": "sponsor_" + SUFFIX, "password": "ChangeMe_123456", "role": "SPONSOR",
               "displayName": "北京示例科技有限公司", "name": "北京示例科技有限公司", "city": "北京"},
         pick={"sp_login": "account.username", "sp_addr": "chainAddress"})
    sp_addr = s["sp_addr"]

    # ---------- 6. ADMIN 给公司统筹账户注资 ----------
    step("公司注资 100000 元", "POST", "/api/admin/deposit", token=admin,
         body={"sponsor": sp_addr, "amount": "100000"})
    step("查公司账户 GET /api/admin/sponsors/{addr}", "GET",
         "/api/admin/sponsors/" + sp_addr, token=admin)

    # ---------- 7. 社保局/公司/职工 登录 ----------
    step("北京局登录", "POST", "/api/auth/login",
         body={"username": s["bj_login"], "password": "ChangeMe_123456"},
         pick={"bj_token": "token"})
    step("上海局登录", "POST", "/api/auth/login",
         body={"username": s["sh_login"], "password": "ChangeMe_123456"},
         pick={"sh_token": "token"})
    step("公司登录", "POST", "/api/auth/login",
         body={"username": s["sp_login"], "password": "ChangeMe_123456"},
         pick={"sp_token": "token"})
    bj, sh, sp = s["bj_token"], s["sh_token"], s["sp_token"]

    # ---------- 8. 公司开职工账号并建档 ----------
    emp = {}
    step("公司开职工账号", "POST", "/api/sponsor/accounts", token=sp,
         body={"username": "emp_" + SUFFIX, "password": "ChangeMe_123456", "displayName": "张三"},
         pick={"emp_login": "account.username", "emp_addr": "chainAddress"})
    emp_addr = s["emp_addr"]
    print("      职工链上地址: %s" % emp_addr)

    step("为职工建档 addEmployee", "POST", "/api/sponsor/employees", token=sp,
         body={"employee": emp_addr, "name": "张三", "age": 30,
               "idNumber": "1101011990010%d" % (int(SUFFIX) % 9) + "XXXX",
               "employer": "北京示例科技有限公司",
               "startDate": 1609459200, "salary": 8000})
    step("调整工资 9000", "POST", "/api/sponsor/employees/salary", token=sp,
         body={"employee": emp_addr, "salary": "9000"})
    step("为职工缴费(个人8%+统筹19%)", "POST", "/api/sponsor/payments", token=sp,
         body={"address": emp_addr})
    step("查公司账户", "GET", "/api/sponsor/account?sponsor=" + sp_addr, token=sp)
    step("查职工明细", "GET", "/api/sponsor/employees/detail", token=sp)
    step("查职工缴费记录", "GET", "/api/sponsor/payments?employee=" + emp_addr, token=sp)

    # ---------- 9. 职工视角 ----------
    step("职工登录", "POST", "/api/auth/login",
         body={"username": s["emp_login"], "password": "ChangeMe_123456"},
         pick={"emp_token": "token"})
    et = s["emp_token"]
    step("职工查参保账户", "GET", "/api/employee/account?employee=" + emp_addr, token=et)
    step("查城市社保局绑定", "GET", "/api/employee/bureau-of-city?city=%E4%B8%8A%E6%B5%B7", token=et)

    # ---------- 10. 转移全流程：减员 → 申请 → 放行(托管) → 接收 ----------
    step("公司减员(停缴,转移前置)", "POST", "/api/sponsor/employees/suspend", token=sp,
         body={"employee": emp_addr, "reason": "工作调动"})
    step("职工发起转移申请(去上海)", "POST", "/api/employee/transfer/apply", token=et,
         body={"toCity": "上海"})
    step("查转移状态取申请编号", "GET", "/api/employee/transfer-state?employee=" + emp_addr,
         token=et, pick={"tid": "activeTransferRef"})

    tid = s.get("tid")
    if isinstance(tid, str) and tid.isdigit():
        tid = int(tid)
    if tid is None or tid == 0:   # 字段名兼容/在途为空
        _, r = call("GET", "/api/employee/transfer-state?employee=" + emp_addr, token=et)
        print("      transfer-state 原始返回: %s" % json.dumps(r.get("data", {}), ensure_ascii=False)[:300])
        raise Abort("未取得在途转移申请编号")

    step("转出局放行(锁定资金到托管)", "POST", "/api/bureau/transfers/authorize", token=bj,
         body={"id": tid, "approved": True, "reason": "同意转出"})
    step("查在途托管金额 escrow", "GET", "/api/employee/escrow?employee=" + emp_addr, token=et)
    step("职工查转移详情", "GET", "/api/employee/transfer/%s" % tid, token=et)
    step("转入局接收(释放托管资金)", "POST", "/api/bureau/transfers/%s/receive" % tid, token=sh)
    step("转移后查职工参保账户(应为上海/TRANSFERRED)", "GET",
         "/api/employee/account?employee=" + emp_addr, token=et)

    # ---------- 11. 监管端 ----------
    step("监管员登录", "POST", "/api/auth/login",
         body={"username": "reg_" + SUFFIX, "password": "ChangeMe_123456"},
         pick={"reg_token": "token"})
    rg = s["reg_token"]
    step("监管大盘 GET /api/regulator/dashboard", "GET", "/api/regulator/dashboard", token=rg)
    step("监管系统统计", "GET", "/api/regulator/system-stats", token=rg)
    step("监管查转移详情", "GET", "/api/regulator/transfers/%s" % tid, token=rg)
    step("监管查转移计数", "GET", "/api/regulator/transfers/count", token=rg)
    step("监管查社保局计数", "GET", "/api/regulator/bureaus/count", token=rg)
    step("监管查城市池", "GET", "/api/regulator/city-pool", token=rg)

    # ---------- 12. 事件/流水（只读，软校验） ----------
    step("事件列表 GET /api/index/events", "GET", "/api/index/events", token=admin, critical=False)
    step("交易日志 GET /api/index/tx-logs", "GET", "/api/index/tx-logs", token=admin, critical=False)

    summary()


if __name__ == "__main__":
    try:
        main()
    except Abort as e:
        print("中止: %s" % e)
        summary()
