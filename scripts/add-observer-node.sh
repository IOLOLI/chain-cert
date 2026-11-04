#!/bin/bash
# 在既有 4 节点链上新增 node4（监管观察节点 / observer）
# 原则：不重建链、不动 node0-3 的数据与配置，只在同目录下新增一个节点目录
set -e

BASE=$HOME/fisco/nodes/127.0.0.1
CERT=$HOME/fisco/nodes/cert
cd "$BASE"

echo "== 0 现状 =="
ls -d node*/

echo "== 1 生成 node4 密钥 =="
rm -f /tmp/n4.* /tmp/n4ch.* /tmp/n4.csr /tmp/n4ch.csr
openssl ecparam -name secp256k1 -genkey -noout -out /tmp/n4.ec.key 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in /tmp/n4.ec.key -out /tmp/n4.node.key
# channel 私钥沿用 node0 的密钥类型（RSA 或 EC），保证握手算法一致
if head -1 node0/conf/channel_cert/node.key | grep -qi "RSA"; then
  openssl genrsa -out /tmp/n4.ch.key 2048 2>/dev/null
  CHALG=RSA
else
  openssl ecparam -name secp256k1 -genkey -noout -out /tmp/n4ch.ec.key 2>/dev/null
  openssl pkcs8 -topk8 -nocrypt -in /tmp/n4ch.ec.key -out /tmp/n4.ch.key
  CHALG=EC
fi
echo "channel key alg: $CHALG"

echo "== 2 计算 nodeid 并自检 =="
nid_of() {
  openssl ec -in "$1" -text -noout 2>/dev/null \
    | awk '/pub:/{f=1;next}/ASN1/{f=0}f' | tr -d ' :\n' | sed 's/^04//'
}
NID=$(nid_of /tmp/n4.node.key)
CHK=$(nid_of node0/conf/node.key)
STORED=$(cat node0/conf/node.nodeid)
echo "node4 nodeid(${#NID}): $NID"
[ "$CHK" = "$STORED" ] && echo "自检通过：node0 nodeid 推导与 conf 一致" || { echo "自检失败: $CHK != $STORED"; exit 1; }

echo "== 3 签证书 =="
cat > /tmp/n4.ext <<'EXT'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,nonRepudiation,keyEncipherment
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid
EXT
# channel CA 证书没有 subjectKeyIdentifier，不能再写 authorityKeyIdentifier=keyid，
# 否则 openssl 报 "unable to get issuer keyid"（本机实测踩过）。
cat > /tmp/n4ch.ext <<'EXT'
subjectKeyIdentifier=hash
EXT

openssl req -new -key /tmp/n4.node.key -out /tmp/n4.csr -subj "/CN=node4/O=fisco-bcos/OU=node" 2>/dev/null
openssl x509 -req -in /tmp/n4.csr -CA "$CERT/agency/agency.crt" -CAkey "$CERT/agency/agency.key" \
  -CAcreateserial -out /tmp/n4.crt -days 36500 -sha256 -extfile /tmp/n4.ext 2>/dev/null

openssl req -new -key /tmp/n4.ch.key -out /tmp/n4ch.csr -subj "/CN=FISCO-BCOS/O=fisco-bcos/OU=agency" 2>/dev/null
openssl x509 -req -in /tmp/n4ch.csr -CA "$CERT/agency/channel/ca.crt" -CAkey "$CERT/agency/channel/ca.key" \
  -CAcreateserial -out /tmp/n4ch.crt -days 36500 -sha256 -extfile /tmp/n4ch.ext 2>/dev/null

echo "== 4 组装 node4 目录 =="
rm -rf node4
cp -r node0 node4
rm -rf node4/data node4/log node4/nohup.out
mkdir -p node4/data node4/log

cp /tmp/n4.node.key node4/conf/node.key
cat /tmp/n4.crt "$CERT/agency/agency.crt" "$CERT/ca.crt" > node4/conf/node.crt
echo "$NID" > node4/conf/node.nodeid
cp node0/conf/ca.crt node4/conf/ca.crt

cp /tmp/n4.ch.key node4/conf/channel_cert/node.key
cp /tmp/n4ch.crt node4/conf/channel_cert/node.crt
cp node0/conf/channel_cert/ca.crt node4/conf/channel_cert/ca.crt

echo "== 5 改端口与 peers =="
sed -i 's/^\( *\)listen_port=30300/\1listen_port=30304/; s/^\( *\)channel_listen_port=20200/\1channel_listen_port=20204/; s/^\( *\)jsonrpc_listen_port=8545/\1jsonrpc_listen_port=8549/' node4/config.ini
sed -i '/^ *node\.[0-9]=/d' node4/config.ini
python3 - node4/config.ini <<'PY'
import sys
p = sys.argv[1]
lines = open(p, encoding='utf-8').read().split('\n')
out, done = [], False
for ln in lines:
    out.append(ln)
    if ln.strip() == '[p2p]' and not done:
        for i in range(4):
            out.append('    node.%d=127.0.0.1:%d' % (i, 30300 + i))
        done = True
open(p, 'w', encoding='utf-8').write('\n'.join(out))
print('peers injected:', done)
PY
sed -n '/\[p2p\]/,/^\[/p' node4/config.ini
echo "-- ports"; grep -E "listen_port|channel_listen_port|jsonrpc_listen_port" node4/config.ini

echo "== 6 证书自检 =="
echo "node.crt blocks: $(grep -c 'BEGIN CERT' node4/conf/node.crt)  channel: $(grep -c 'BEGIN CERT' node4/conf/channel_cert/node.crt)"
openssl verify -CAfile node4/conf/ca.crt -untrusted "$CERT/agency/agency.crt" node4/conf/node.crt 2>&1 | head -2
openssl verify -CAfile node4/conf/channel_cert/ca.crt node4/conf/channel_cert/node.crt 2>&1 | head -2
echo "== done =="
