# 链拓扑：4 共识节点 + 1 监管观察节点

ChainSIP 现在跑在一条 **5 节点** 的 FISCO BCOS 2.11 群组（group 1）上：4 个节点出块，
第 5 个节点是**监管节点**，以 observer（观察节点）身份加入同一群组。

```
                    ┌───────────────── group 1 ─────────────────┐
                    │                                            │
   node0 (sealer) ──┼── node1 (sealer) ── node2 (sealer) ── node3 (sealer)
   7d20e725…          53abf16c…            a7f1d925…            aadb6cda…
        │                  │                  │                  │
        └──────────────────┴──────────────────┴──────────────────┘
                                   │
                          node4 (observer) ← 监管节点
                          b6b84d43a6320dc5e0c6ede0a6c04cef
```

## 为什么监管节点是 observer

| | sealer（共识节点） | observer（监管节点） |
| --- | --- | --- |
| 同步账本 | 是 | 是（全量，逐块校验） |
| 打包 / 出块 | 是 | **否** |
| PBFT 投票权 | 是 | **否** |
| 能发交易 | 是 | 是（业务权限仍由合约裁决） |

监管只要「看得见、可复核、可举证」，不需要「能改」。把监管方放进 sealer 列表会有两个后果：
一是监管节点宕机直接拉低共识容错（4 节点容错 1，5 节点容错 1 但法定人数变成 4，反而更脆）；
二是监管与经办混在同一个投票集合里，"独立第三方"这个论证就不成立了。observer 正好落在中间：
它拿到和共识节点完全一样的账本，可以自己重算三段恒等式，但任何一笔写操作仍要经过合约的 `msg.sender` 角色判定。

## 节点清单

| 节点 | 身份 | nodeid（前 16 位） | p2p | channel | jsonrpc |
| --- | --- | --- | --- | --- | --- |
| node0 | sealer | `7d20e7258ff87c3c` | 30300 | 20200 | 8545 |
| node1 | sealer | `53abf16c10201d92` | 30301 | 20201 | 8546 |
| node2 | sealer | `a7f1d925a9fbccef` | 30302 | 20202 | 8547 |
| node3 | sealer | `aadb6cda5c5bb461` | 30303 | 20203 | 8548 |
| node4 | **observer（监管）** | `b6b84d43a6320dc5` | 30304 | 20204 | 8549 |

整套环境都在虚拟机 `<CHAIN_HOST>` 的 `~/fisco/nodes/127.0.0.1/` 下；
后端 SDK（`backend/sdk/config.toml`）仍只连 node0 的 20200，监管侧读账可以另外连 20204。

## 怎么加的（没有重建链）

监管节点是**热加入**的，原有链数据、合约地址、账号地址一个都没动：

1. 用 `cert/agency` 给 node4 签一张节点证书（`CN=node4`），channel 证书走 `cert/agency/channel`；
2. 复制 node0 的组配置（`group.1.genesis` / `group.1.ini`），清空 `data/`，改端口为 30304/20204/8549；
3. 启动 node4 —— 此时它只是 free node（能连上、但不在群组里，RPC 会报
   `Don't send request to this node who doesn't belong to the group`）；
4. 控制台执行 `addObserver <node4 完整 nodeid>`，返回 `{"code":1,"msg":"Success"}` 后它进组，
   开始全量同步，`getSyncStatus` 里 `isSyncing=false` 且 `blockNumber` 与 node0 一致。

脚本留在 `.workbuddy/setup_regulator_node.sh`（在虚拟机上执行），可重复用于再挂节点。

## 怎么验

链上（虚拟机）：

```bash
cd ~/fisco/console
printf 'getSealerList\ngetObserverList\ngetNodeIDList\n' | timeout 60 ./start.sh
```

- `getSealerList` → 4 条
- `getObserverList` → 1 条（`b6b84d43…`）
- `getNodeIDList` → 5 条

平台侧（新增只读接口 `GET /api/chain/topology`）：

```json
{ "sealerCount": 4, "observerCount": 1, "nodeCount": 5, "description": "4 个共识节点 + 1 个观察节点" }
```

首页页眉的「链网关」格与监管大屏的「监管节点」条带都读这个接口，
所以界面上看到的拓扑是**实时从链上查出来的**，不是写死的文案。

## 监管账户

| 项 | 值 |
| --- | --- |
| 账号 | `regulator_demo` / `ChangeMe_123456` |
| 链下角色 | `REGULATOR`（链下只决定界面可见性） |
| 链上角色 | `REGULATOR`（合约 `Roles` 枚举 4，创建时自动 `addRegulatorAccount`） |
| 链上地址 | `<CONTRACT_ADDRESS>` |
| 机构编码 | `REGULATOR_AE1A5C` |
| 可见页面 | 首页概览 / 监管大屏 / 链上索引 / 合约调试台 |

合约对 REGULATOR 的授权是**只读**：`getSystemStats`、`getSponsorList`、`getBureauList`、
转移查询都能读，注资 / 缴费 / 建档 / 放行一律回滚。所以监管账户即使拿到监管节点的私钥，
也只能在链上留下"读过"的痕迹，改不了任何一个数字——这是本项目的核心论证之一。

## 后续（答辩时可能被问）

- **能不能删掉监管节点？** 可以，`removeNode <nodeid>`，不影响出块（它本来就不投票）；
  但删掉之后监管方就只剩链下索引，失去"自己重算账本"的能力。
- **4 个共识节点容错多少？** 1 个。挂 2 个就停在 2 个节点，PBFT 无法达成 3f+1=3 的法定人数，
  链会停止出块但不丢数据，恢复后继续。
- **多机部署怎么办？** 把 node4 的目录整份拷到监管方机器，`config.ini` 里把 `listen_ip` 改成自己的地址、
  `[p2p]` 里填 4 个共识节点的对外地址即可；证书不用重签（nodeid 由 node.key 决定，与 IP 无关）。
