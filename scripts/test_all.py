#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ChainSIP 区块链养老保险平台 —— 全功能端到端测试（仅用 Python 标准库，无需 pip install）。

覆盖的业务域（按依赖顺序串联成一条完整链路，每次运行用时间戳生成唯一城市/用户名，可重复执行）：
  0  健康检查 / 链状态 / 链浏览器
  1  认证（登录 / 我的信息 / 联调签名）
  2  政策参数（字典 / 参数 / 生效清单 / 链上对账 / 发布 / 审计 / 改动并还原）
  3  机构管理（ADMIN 开社保局×2、监管、缴费单位；社保局开缴费单位；注资；机构私钥；待注册）
  4  参保（单位开职工、建档、调薪、缴费、试算）
  5  职工视角（账户 / 缴费记录 / 城市绑定）
  6  转移办理（减员 → 申请 → 放行托管 → 接收 → 接续参保 → 缴费）
  7  养老金支出（规则 / 总览 / 可办退休 / 特殊审批退休 / 发放 / 流水 / 核定单复算）
  8  辖区总览（本局服务城市内的单位 / 职工 / 参保记录 / 缴纳记录）
  9  监管审计（大盘 / 资金对账 / 城市基金池 / 转移与机构名单）
 10  链浏览器 / 链下索引（区块 / 交易 / 地址 / 事件 / 上链日志）

用法：
  python test_all.py                          # 默认 http://127.0.0.1:8080
  python test_all.py http://<BACKEND_HOST>:8080 # 指定后端地址

前置：后端已启动、MySQL 与 FISCO BCOS 链就绪、admin/ChangeMe_123456 引导账号可用（DataSeeder 自动创建）。
退出码：全部通过为 0，有失败为 1。
"""
import json
import os
import secrets
import sys
import time
import urllib.parse
import urllib.request
import urllib.error

BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else os.environ.get("CHAINSIP_BASE_URL", "http://127.0.0.1:8080")
SUFFIX = str(int(time.time()))[-6:]          # 唯一后缀
CITY_A = "甲市" + SUFFIX                       # 转出地
CITY_B = "乙市" + SUFFIX                       # 转入地
ADMIN = (os.environ.get("CHAINSIP_ADMIN_USER", "admin"),
         os.environ.get("CHAINSIP_ADMIN_PASSWORD", "ChangeMe_123456"))
TEST_PRIVATE_KEY = secrets.token_hex(32)   # 仅用于 /demo/sign，随机生成，不保存

RESULTS = []  # (name, ok, http, detail)


def call(method, path, token=None, body=None, timeout=60):
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


def _pick(data, expr):
    """按点号路径从 data 里取值：a.b.c / a.0.b。"""
    cur = data
    for part in expr.split("."):
        if isinstance(cur, dict):
            cur = cur.get(part)
        elif isinstance(cur, list) and part.isdigit():
            idx = int(part)
            cur = cur[idx] if idx < len(cur) else None
        else:
            return None
        if cur is None:
            return None
    return cur


def login(username, password):
    status, resp = call("POST", "/api/auth/login", body={"username": username, "password": password})
    if status != 200 or resp.get("code") != 0 or not resp.get("data", {}).get("token"):
        raise RuntimeError("登录失败 %s: HTTP %d %s" % (username, status, resp.get("message", "")))
    return resp["data"]["token"]


def step(name, method, path, token=None, body=None, critical=True, pick=None, store=None, check=None):
    status, resp = call(method, path, token, body)
    code = resp.get("code") if isinstance(resp, dict) else None
    data = resp.get("data") if isinstance(resp, dict) else None
    ok = (status == 200) and (code == 0)
    detail = ""
    if ok and pick and store is not None:
        for key, expr in pick.items():
            store[key] = _pick(data, expr)
    # 可选强断言：check(data) 返回 (ok, detail)，用于校验非空/字段值，避免"空数据也全绿"
    if ok and check is not None:
        ok, detail = check(data)
    if not ok and not detail:
        detail = "HTTP %d code=%s msg=%s" % (status, code, str(resp.get("message", ""))[:120])
    RESULTS.append((name, ok, status, detail))
    mark = "\033[32mPASS\033[0m" if ok else "\033[31mFAIL\033[0m"
    print("[%s] %s%s" % (mark, name, ("  " + detail) if detail else ""))
    if critical and not ok:
        print("\n关键步骤失败，中止后续测试。")
        summary()
        sys.exit(1)
    return ok


def _events_check(names):
    """断言事件索引里必须包含指定事件名（校验 D-01：事件索引不能为空）。"""
    def check(data):
        records = data.get("records") or []
        found = {r.get("eventName") for r in records if isinstance(r, dict)}
        if not records:
            return False, "事件索引为空（total=%s）" % data.get("total")
        missing = set(names) - found
        if missing:
            return False, "事件索引缺少事件 %s（仅含 %s）" % (sorted(missing), sorted(found))
        return True, ""
    return check


def _records_check():
    """断言养老金发放流水非空且金额 > 0（校验 D-01：流水按职工可查）。"""
    def check(data):
        records = data.get("records") or []
        total = data.get("total")
        if not records or not total:
            return False, "养老金发放流水为空（total=%s）" % total
        first = records[0] if isinstance(records[0], dict) else {}
        raw = first.get("totalAmount") or "0"
        try:
            if float(raw) <= 0:
                return False, "发放流水金额为 0：%s" % first
        except (TypeError, ValueError):
            return False, "发放流水金额无法解析：%s" % first
        return True, ""
    return check


def summary():
    total = len(RESULTS)
    failed = [r for r in RESULTS if not r[1]]
    print("\n" + "=" * 64)
    print("测试汇总: %d 项, 通过 %d, 失败 %d" % (total, total - len(failed), len(failed)))
    for name, ok, status, detail in failed:
        print("  [FAIL] %s (HTTP %d) %s" % (name, status, detail))
    print("=" * 64)
    if failed:
        sys.exit(1)


def main():
    print("目标后端: %s  转出地=%s  转入地=%s\n" % (BASE, CITY_A, CITY_B))
    s = {}

    # ============ 0. 健康检查 / 链状态 / 链浏览器 ============
    step("健康检查 GET /api/public/health（免登录）", "GET", "/api/public/health")
    admin = login(ADMIN[0], ADMIN[1])
    s["admin"] = admin
    step("ADMIN 登录", "POST", "/api/auth/login",
         body={"username": ADMIN[0], "password": ADMIN[1]},
         pick={"admin_addr": "account.chainAddress"}, store=s)
    step("链网关状态 GET /api/chain/status", "GET", "/api/chain/status", token=admin)
    step("最新区块 GET /api/chain/block", "GET", "/api/chain/block", token=admin)
    step("资金总览 GET /api/chain/stats", "GET", "/api/chain/stats", token=admin)
    step("共识拓扑 GET /api/chain/topology", "GET", "/api/chain/topology", token=admin)
    step("链浏览器概览 GET /api/chain/explorer/overview", "GET", "/api/chain/explorer/overview", token=admin)
    step("链浏览器区块列表 GET /api/chain/explorer/blocks", "GET", "/api/chain/explorer/blocks?limit=3", token=admin)

    # ============ 1. 认证 ============
    step("我的信息 GET /api/auth/me", "GET", "/api/auth/me", token=admin)
    step("联调签名 POST /api/auth/demo/sign", "POST", "/api/auth/demo/sign", token=admin,
         body={"privateKey": TEST_PRIVATE_KEY,
               "message": "test_all"})

    # ============ 2. 政策参数 ============
    step("参数字典 GET /api/admin/policy/definitions", "GET", "/api/admin/policy/definitions", token=admin)
    step("参数详情 GET /api/admin/policy/parameters", "GET",
         "/api/admin/policy/parameters?code=contrib.employee.rate", token=admin)
    step("生效参数清单 GET /api/admin/policy/effective", "GET", "/api/admin/policy/effective", token=admin)
    step("链上对账 GET /api/admin/policy/chain/status", "GET", "/api/admin/policy/chain/status", token=admin)
    step("发布政策上链 POST /api/admin/policy/chain/publish", "POST", "/api/admin/policy/chain/publish",
         token=admin, body={})
    step("参数变更审计 GET /api/admin/policy/audit", "GET", "/api/admin/policy/audit", token=admin)
    # 改动 → 还原：验证"参数追加版本 + 自动发布上链"
    step("调整个人费率 8%→9% POST /api/admin/policy/parameters", "POST", "/api/admin/policy/parameters",
         token=admin, body={"paramCode": "contrib.employee.rate", "regionCode": "NATIONAL",
                            "paramValue": "0.09", "reason": "test_all 全功能验证"})
    step("还原个人费率 9%→8% POST /api/admin/policy/parameters", "POST", "/api/admin/policy/parameters",
         token=admin, body={"paramCode": "contrib.employee.rate", "regionCode": "NATIONAL",
                            "paramValue": "0.08", "reason": "test_all 验证结束，恢复默认"})

    # ============ 3. 机构管理 ============
    step("开社保局A(%s)" % CITY_A, "POST", "/api/admin/accounts", token=admin,
         body={"username": "bureau_a_" + SUFFIX, "password": "ChangeMe_123456", "role": "BUREAU",
               "displayName": "社保局A", "name": "社保局A", "city": CITY_A},
         pick={"bureau_a_login": "account.username", "bureau_a_addr": "chainAddress"}, store=s)
    step("开社保局B(%s)" % CITY_B, "POST", "/api/admin/accounts", token=admin,
         body={"username": "bureau_b_" + SUFFIX, "password": "ChangeMe_123456", "role": "BUREAU",
               "displayName": "社保局B", "name": "社保局B", "city": CITY_B},
         pick={"bureau_b_login": "account.username", "bureau_b_addr": "chainAddress"}, store=s)
    step("开监管账号", "POST", "/api/admin/accounts", token=admin,
         body={"username": "reg_" + SUFFIX, "password": "ChangeMe_123456", "role": "REGULATOR",
               "displayName": "基金监管员", "name": "基金监管员"},
         pick={"reg_login": "account.username"}, store=s)
    step("ADMIN 开缴费单位A(%s)" % CITY_A, "POST", "/api/admin/accounts", token=admin,
         body={"username": "sponsor_a_" + SUFFIX, "password": "ChangeMe_123456", "role": "SPONSOR",
               "displayName": "单位A", "name": "单位A", "city": CITY_A},
         pick={"sponsor_a_login": "account.username", "sponsor_a_addr": "chainAddress"}, store=s)
    step("给单位A注资 200000", "POST", "/api/admin/deposit", token=admin,
         body={"sponsor": s["sponsor_a_addr"], "amount": "200000"})
    step("查单位A资金账户 GET /api/admin/sponsors/{addr}", "GET",
         "/api/admin/sponsors/" + s["sponsor_a_addr"], token=admin)
    step("平台账号列表 GET /api/admin/accounts", "GET", "/api/admin/accounts", token=admin)
    step("机构链账户列表 GET /api/admin/institution-keys", "GET", "/api/admin/institution-keys", token=admin)
    step("已注册城市 GET /api/admin/cities", "GET", "/api/admin/cities", token=admin)
    step("平台总览 GET /api/admin/system-stats", "GET", "/api/admin/system-stats", token=admin)
    step("待上链注册列表 GET /api/admin/institutions/pending", "GET", "/api/admin/institutions/pending", token=admin)

    # 角色登录
    ba = login(s["bureau_a_login"], "ChangeMe_123456")
    bb = login(s["bureau_b_login"], "ChangeMe_123456")
    s["ba"], s["bb"] = ba, bb
    # 社保局B 开缴费单位B（测试 社保局→单位 层级）
    step("社保局B 开缴费单位B(%s)" % CITY_B, "POST", "/api/bureau/accounts", token=bb,
         body={"username": "sponsor_b_" + SUFFIX, "password": "ChangeMe_123456",
               "displayName": "单位B", "name": "单位B"},
         pick={"sponsor_b_login": "account.username", "sponsor_b_addr": "chainAddress"}, store=s)
    step("给单位B注资 200000", "POST", "/api/admin/deposit", token=admin,
         body={"sponsor": s["sponsor_b_addr"], "amount": "200000"})

    # ============ 4. 参保 ============
    sa = login(s["sponsor_a_login"], "ChangeMe_123456")
    s["sa"] = sa
    step("单位A 开职工甲账号", "POST", "/api/sponsor/accounts", token=sa,
         body={"username": "emp1_" + SUFFIX, "password": "ChangeMe_123456", "displayName": "职工甲"},
         pick={"emp1_login": "account.username", "emp1_addr": "chainAddress"}, store=s)
    step("单位A 开职工乙账号", "POST", "/api/sponsor/accounts", token=sa,
         body={"username": "emp2_" + SUFFIX, "password": "ChangeMe_123456", "displayName": "职工乙"},
         pick={"emp2_login": "account.username", "emp2_addr": "chainAddress"}, store=s)
    # 18 位身份证号：合约只做“非空 + 全局唯一”校验，无格式/校验位校验；
    # 用毫秒时间戳拼成 18 位，保证每次运行都不撞号（接续搜索走 idMode 精确匹配）。
    _ts13 = str(int(time.time() * 1000))
    s["emp1_id"] = "11010" + _ts13
    s["emp2_id"] = "11020" + _ts13
    step("职工甲建档 addEmployee", "POST", "/api/sponsor/employees", token=sa,
         body={"employee": s["emp1_addr"], "name": "职工甲", "age": 35,
               "idNumber": s["emp1_id"],
               "employer": "单位A", "startDate": 1609459200, "salary": 8000})
    step("职工乙建档 addEmployee", "POST", "/api/sponsor/employees", token=sa,
         body={"employee": s["emp2_addr"], "name": "职工乙", "age": 62,
               "idNumber": s["emp2_id"],
               "employer": "单位A", "startDate": 1609459200, "salary": 8000})
    step("职工甲调薪 9000", "POST", "/api/sponsor/employees/salary", token=sa,
         body={"employee": s["emp1_addr"], "salary": "9000"})
    step("职工甲缴费(8%+19%)", "POST", "/api/sponsor/payments", token=sa,
         body={"address": s["emp1_addr"]})
    step("职工乙缴费(8%+19%)", "POST", "/api/sponsor/payments", token=sa,
         body={"address": s["emp2_addr"]})
    step("缴费试算 GET /api/sponsor/payments/quote", "GET", "/api/sponsor/payments/quote?salary=10000", token=sa)
    step("单位A账户 GET /api/sponsor/account", "GET", "/api/sponsor/account?sponsor=" + s["sponsor_a_addr"], token=sa)
    step("单位A职工明细 GET /api/sponsor/employees/detail", "GET", "/api/sponsor/employees/detail", token=sa)
    step("职工甲缴费记录 GET /api/sponsor/payments", "GET", "/api/sponsor/payments?employee=" + s["emp1_addr"], token=sa)
    step("职工甲参保账户 GET /api/sponsor/employees/account", "GET",
         "/api/sponsor/employees/account?employee=" + s["emp1_addr"], token=sa)

    # ============ 5. 职工视角 ============
    e1 = login(s["emp1_login"], "ChangeMe_123456")
    e2 = login(s["emp2_login"], "ChangeMe_123456")
    s["e1"], s["e2"] = e1, e2
    step("职工甲查参保账户 GET /api/employee/account", "GET",
         "/api/employee/account?employee=" + s["emp1_addr"], token=e1)
    step("职工甲查缴费记录 GET /api/employee/payments", "GET",
         "/api/employee/payments?employee=" + s["emp1_addr"], token=e1)
    step("查城市社保局绑定 GET /api/employee/bureau-of-city", "GET",
         "/api/employee/bureau-of-city?city=" + urllib.parse.quote(CITY_B), token=e1)

    # ============ 6. 转移办理（核心）============
    step("单位A 减员停缴（转移前置）", "POST", "/api/sponsor/employees/suspend", token=sa,
         body={"employee": s["emp1_addr"], "reason": "跨市转移"})
    step("职工甲发起转移申请 %s → %s" % (CITY_A, CITY_B), "POST", "/api/employee/transfer/apply", token=e1,
         body={"toCity": CITY_B})
    # 从转出待办取申请编号（合约 getOutboundTransfers 返回 id 数组，末位即最新申请）
    st, rsp = call("GET", "/api/bureau/transfers/outbound?bureau=%s&offset=0&limit=50" % s["bureau_a_addr"], ba)
    out = (rsp or {}).get("data", {}) or {}
    ids = out.get("ids") or out.get("list") or []
    tid = ids[-1] if ids else None
    if tid is None:
        RESULTS.append(("转出地待办可查到申请单", False, st,
                        "ids 为空: " + json.dumps(out, ensure_ascii=False)[:160]))
        print("[FAIL] 转出地待办可查到申请单")
        summary(); sys.exit(1)
    RESULTS.append(("转出地待办可查到申请单", True, st, ""))
    print("[PASS] 转出地待办可查到申请单  id=%s" % tid)
    s["tid"] = tid
    step("转出局放行（锁基金托管）POST /api/bureau/transfers/authorize", "POST",
         "/api/bureau/transfers/authorize", token=ba,
         body={"id": int(tid), "approved": True, "reason": "同意转出"})
    step("查在途托管金额 GET /api/employee/escrow", "GET",
         "/api/employee/escrow?employee=" + s["emp1_addr"], token=e1)
    step("查转移详情 GET /api/employee/transfer/{id}", "GET", "/api/employee/transfer/" + str(tid), token=e1)
    step("转入局接收（建立新参保档案）POST /api/bureau/transfers/{id}/receive", "POST",
         "/api/bureau/transfers/" + str(tid) + "/receive", token=bb, body={})
    step("转移后职工账户应为%s/待参保" % CITY_B, "GET",
         "/api/employee/account?employee=" + s["emp1_addr"], token=e1)
    # 接续参保（方案A：转入地单位按地址接续，姓名/身份证/基数取链上档案）
    sb = login(s["sponsor_b_login"], "ChangeMe_123456")
    s["sb"] = sb
    step("单位B 搜索待接续职工（按身份证） GET /api/sponsor/employees/rehire-search", "GET",
         "/api/sponsor/employees/rehire-search?keyword=" + s["emp1_id"], token=sb)
    step("单位B 接续参保 POST /api/sponsor/employees/rehire", "POST", "/api/sponsor/employees/rehire",
         token=sb, body={"address": s["emp1_addr"]})
    # 验证接续结果：职工甲应归属单位B、参保城市为转入地
    st, rsp = call("GET", "/api/sponsor/employees/account?employee=" + s["emp1_addr"], sb)
    acct = (rsp or {}).get("data", {}) or {}
    ok_join = (st == 200 and (rsp or {}).get("code") == 0
               and str(acct.get("sponsor") or "").lower() == s["sponsor_b_addr"].lower()
               and acct.get("city") == CITY_B and acct.get("status") == 1)
    RESULTS.append(("接续后职工甲归属单位B且城市=%s/在职" % CITY_B, ok_join, st,
                    "" if ok_join else json.dumps(acct, ensure_ascii=False)[:160]))
    print("[%s] 接续后职工甲归属单位B且城市=%s/在职%s" % (
        "\033[32mPASS\033[0m" if ok_join else "\033[31mFAIL\033[0m", CITY_B,
        "" if ok_join else "  " + json.dumps(acct, ensure_ascii=False)[:160]))
    if not ok_join:
        summary(); sys.exit(1)
    step("单位B 缴费一个月（验证接续可用）", "POST", "/api/sponsor/payments",
         token=login(s["sponsor_b_login"], "ChangeMe_123456"), body={"address": s["emp1_addr"]})

    # ============ 7. 养老金支出（职工乙，仍在 CITY_A 辖区）============
    step("养老金规则 GET /api/bureau/pension/rules", "GET", "/api/bureau/pension/rules", token=ba)
    step("养老金总览 GET /api/bureau/pension/overview", "GET", "/api/bureau/pension/overview", token=ba)
    step("可办退休名单 GET /api/bureau/pension/eligible", "GET", "/api/bureau/pension/eligible", token=ba)
    step("特殊审批退休（职工乙, force）POST /api/bureau/pension/retire", "POST",
         "/api/bureau/pension/retire", token=ba, body={"employee": s["emp2_addr"], "force": True})
    step("已退休人员 GET /api/bureau/pension/retirees", "GET", "/api/bureau/pension/retirees", token=ba)
    step("发放一个月养老金 POST /api/bureau/pension/claim", "POST", "/api/bureau/pension/claim",
         token=ba, body={"employee": s["emp2_addr"]})
    step("养老金发放流水 GET /api/bureau/pension/records", "GET",
         "/api/bureau/pension/records?employee=" + s["emp2_addr"], token=ba, check=_records_check())
    step("核定单列表 GET /api/bureau/pension/determinations", "GET",
         "/api/bureau/pension/determinations", token=ba)
    step("职工乙最新核定单 GET /api/bureau/pension/determinations/latest", "GET",
         "/api/bureau/pension/determinations/latest?employee=" + s["emp2_addr"], token=ba)
    step("核定单复算 POST /api/bureau/pension/determinations/recompute", "POST",
         "/api/bureau/pension/determinations/recompute?employee=" + s["emp2_addr"], token=ba, body={})

    # ============ 8. 辖区总览（社保局A）============
    step("辖区汇总 GET /api/bureau/city/overview", "GET", "/api/bureau/city/overview", token=ba)
    step("辖区缴费单位 GET /api/bureau/city/sponsors", "GET", "/api/bureau/city/sponsors", token=ba)
    step("辖区参保职工 GET /api/bureau/city/employees", "GET", "/api/bureau/city/employees", token=ba)
    step("辖区某职工参保记录 GET /api/bureau/city/insurance", "GET",
         "/api/bureau/city/insurance?employee=" + s["emp2_addr"], token=ba)
    step("辖区某职工缴纳记录 GET /api/bureau/city/payments", "GET",
         "/api/bureau/city/payments?employee=" + s["emp2_addr"], token=ba)
    step("本局服务城市 GET /api/bureau/cities", "GET", "/api/bureau/cities", token=ba)
    step("社保局信息 GET /api/bureau/info", "GET", "/api/bureau/info?bureau=" + s["bureau_a_addr"], token=ba)
    step("社保局名单 GET /api/bureau/list", "GET", "/api/bureau/list", token=ba)

    # ============ 9. 监管审计 ============
    rg = login(s["reg_login"], "ChangeMe_123456")
    step("监管大盘 GET /api/regulator/dashboard", "GET", "/api/regulator/dashboard", token=rg)
    step("监管资金对账 GET /api/regulator/system-stats", "GET", "/api/regulator/system-stats", token=rg)
    step("城市基金池 GET /api/regulator/city-pool", "GET",
         "/api/regulator/city-pool?city=" + urllib.parse.quote(CITY_A), token=rg)
    step("转移总数 GET /api/regulator/transfers/count", "GET", "/api/regulator/transfers/count", token=rg)
    step("转移详情 GET /api/regulator/transfers/{id}", "GET", "/api/regulator/transfers/" + str(tid), token=rg)
    step("社保局总数 GET /api/regulator/bureaus/count", "GET", "/api/regulator/bureaus/count", token=rg)
    step("监管账户名单 GET /api/regulator/accounts", "GET", "/api/regulator/accounts", token=rg)
    step("公司名单 GET /api/regulator/sponsors", "GET", "/api/regulator/sponsors", token=rg)

    # ============ 10. 链浏览器 / 链下索引 ============
    step("链浏览器地址详情 GET /api/chain/explorer/addresses/{addr}", "GET",
         "/api/chain/explorer/addresses/" + s["emp1_addr"], token=admin)
    step("链浏览器搜索 GET /api/chain/explorer/search", "GET",
         "/api/chain/explorer/search?q=" + s["emp1_addr"], token=admin)
    step("链下事件索引 GET /api/index/events", "GET", "/api/index/events?size=50", token=admin,
         check=_events_check(["PaymentMade", "EmployeeRetired", "PensionClaimed"]))
    step("链下上链日志 GET /api/index/tx-logs", "GET", "/api/index/tx-logs?size=5", token=admin)

    summary()


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as e:
        print("中止: %s" % e)
        summary()
