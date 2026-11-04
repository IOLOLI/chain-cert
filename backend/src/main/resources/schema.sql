-- ============================================================================
-- ChainSIP 后端数据库结构（MySQL 8，全部幂等：create table if not exists）
-- 只使用标准 MySQL 8 语法（bigint auto_increment / varchar / text / datetime），不含 H2 专有语法。
-- 原则：链上只放账本与权限（合约里），链下放认证凭据、地址绑定、索引与留痕。
--       密码只存 PBKDF2 派生值；机构私钥以 AES-256-GCM 密文落库。
--
-- 注意：二级索引不写在本文件里。create table if not exists 只对新库生效，而 MySQL 8 不支持
--       create index if not exists，写进来会在第二次启动时因 "Duplicate key name" 直接失败。
--       索引统一由启动时的 SchemaIndexMigration 幂等补齐（老库、新库都覆盖，见该类注释里的清单）。
-- ============================================================================

-- 平台账号（登录凭据；角色字段仅用于界面展示与挑选发交易用的私钥，链上角色才是权威）
-- created_by：创建者账号 id（层级开号时写入；系统初始化的 admin 为 NULL）。
--             机构账号列表按登录者范围收口时，用它做"创建归属"判定。
--             老库由 AccountCreatedByMigration 在启动时幂等补列（create table if not exists 不会加列）。
create table if not exists cs_account (
    id bigint auto_increment primary key,
    username varchar(64) not null,
    password_hash varchar(200) not null,
    display_name varchar(64),
    role varchar(16) not null,
    institution_code varchar(64),
    status varchar(16) not null default 'ENABLED',
    created_by bigint,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_cs_account_username unique (username)
);

-- 账号 ↔ 链上地址绑定（必须经过私钥签名验证才算 verified；未验证的绑定不参与发交易）
-- remark：绑定来源备注。用户自带地址走验签绑定；平台自动生成的地址（机构密钥 / EMP_ 职工密钥）
--         由后端在创建账号时自动绑定，此处写明"创建账号时自动生成并绑定"。
--         老库由 WalletSchemaMigration 在启动时幂等补列（create table if not exists 不会加列）。
create table if not exists cs_wallet (
    id bigint auto_increment primary key,
    account_id bigint not null,
    address varchar(64) not null,
    group_id varchar(32) not null default 'group0',
    verified boolean not null default false,
    primary_wallet boolean not null default true,
    bound_at timestamp,
    created_at timestamp not null default current_timestamp,
    remark varchar(300) null,
    constraint uk_cs_wallet_address unique (address, group_id)
);

-- 机构链账户：机构身份 → 链上地址 + 加密私钥（发交易时按登录账号的机构挑选私钥）
create table if not exists cs_institution_key (
    id bigint auto_increment primary key,
    code varchar(64) not null,
    name varchar(128) not null,
    role varchar(16) not null,
    address varchar(64) not null,
    private_key_cipher varchar(1024) not null,
    iv varchar(64) not null,
    enabled boolean not null default true,
    remark varchar(300),
    created_at timestamp not null default current_timestamp,
    constraint uk_cs_institution_code unique (code),
    constraint uk_cs_institution_address unique (address)
);

-- 上链操作留痕（每次 send 都记一笔，便于审计与排障）
create table if not exists cs_chain_tx_log (
    id bigint auto_increment primary key,
    institution_code varchar(64),
    sender_address varchar(64),
    contract_name varchar(64) not null,
    function_name varchar(64) not null,
    params_json varchar(4000),
    tx_hash varchar(80),
    block_number bigint,
    status int,
    success boolean not null default false,
    message varchar(500),
    created_at timestamp not null default current_timestamp
);

-- 链上事件索引（链上名单类查询交给事件驱动，链下可做列表/报表/模糊查询）
create table if not exists cs_chain_event (
    id bigint auto_increment primary key,
    tx_hash varchar(80),
    block_number bigint,
    contract_name varchar(64),
    function_name varchar(64),
    event_name varchar(64),
    employee_address varchar(64),
    sponsor_address varchar(64),
    amount decimal(38, 0),
    personal_amount decimal(38, 0),
    overall_amount decimal(38, 0),
    payload varchar(4000),
    created_at timestamp not null default current_timestamp
);

-- ============================================================================
-- 政策参数化（一期）：参数放链下版本化管理，合约只保留账本与权限。
--
-- 四张表的分工：
--   cs_policy_definition  参数字典（编码 / 类型 / 默认值 / 值域 / 政策文号）
--   cs_policy_parameter   参数版本值（只追加不改写；valid_from/valid_to 表达有效期）
--   cs_policy_audit_log   参数变更审计（谁、何时、为什么改）
--   cs_pension_determination  退休核定单（入参 + 政策快照 + 结果 + 链交易哈希）
--
-- 为什么不写进合约：核心合约运行时代码已逼近 EIP-170 的 24576 B 上限，
-- 且链上存参数表会带来"改值要发交易、历史口径无法冻结"的问题。
-- ============================================================================

-- 参数字典：管理端表单与值域校验的依据；default_value 是参数表缺失时的兜底值
create table if not exists cs_policy_definition (
    id bigint auto_increment primary key,
    param_code varchar(64) not null,
    param_name varchar(128) not null,
    param_type varchar(16) not null,
    default_value varchar(512) not null,
    value_min decimal(18, 6),
    value_max decimal(18, 6),
    region_scope varchar(16) not null default 'NATIONAL',
    category varchar(32) not null,
    status varchar(16) not null default 'ENABLED',
    policy_doc varchar(256),
    remark varchar(500),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_cs_policy_definition_code unique (param_code)
);

-- 参数版本值：同一 (param_code, region_code) 内 version_no 递增；
-- valid_from/valid_to 决定"某个时点取哪个版本"，历史版本只改 valid_to，不改值。
create table if not exists cs_policy_parameter (
    id bigint auto_increment primary key,
    param_code varchar(64) not null,
    region_code varchar(32) not null,
    param_value varchar(512) not null,
    valid_from date not null,
    valid_to date,
    version_no int not null,
    status varchar(16) not null,
    created_by varchar(64) not null,
    created_at timestamp not null default current_timestamp,
    constraint uk_cs_policy_parameter_version unique (param_code, region_code, version_no)
);

-- 参数变更审计：每次追加版本写一行，首次配置 old_value 为空
create table if not exists cs_policy_audit_log (
    id bigint auto_increment primary key,
    param_code varchar(64) not null,
    region_code varchar(32) not null,
    version_no int,
    old_value varchar(256),
    new_value varchar(256) not null,
    effective_date date not null,
    reason varchar(500),
    operator varchar(64) not null,
    operate_at timestamp not null default current_timestamp
);

-- 退休核定单：一次退休办理 = 一行，快照（policy_snapshot）不可变，
-- 事后政策调整不影响历史核定结果，可随时用快照复算。
-- 说明：不建 employee_address 唯一约束——重新办理时旧行置 SUPERSEDED、新行插入，
--       "一人一份有效核定单"由应用层保证（取最新一行）。
create table if not exists cs_pension_determination (
    id bigint auto_increment primary key,
    employee_address varchar(64) not null,
    retire_date date not null,
    retire_age int not null,
    paid_months int not null,
    payment_base decimal(38, 0),
    account_balance decimal(38, 0),
    basic_monthly decimal(38, 0),
    personal_monthly decimal(38, 0),
    monthly_total decimal(38, 0),
    policy_snapshot text not null,
    policy_snapshot_hash varchar(64) not null,
    policy_version_ref varchar(512),
    status varchar(16) not null,
    determination_tx_hash varchar(80),
    source varchar(16) not null default 'LIVE',
    operator varchar(64),
    determined_at timestamp not null default current_timestamp
);

-- 登录日志（认证在链下，登录失败/成功都要留痕）
create table if not exists cs_login_log (
    id bigint auto_increment primary key,
    account_id bigint,
    username varchar(64),
    address varchar(64),
    client_ip varchar(64),
    user_agent varchar(300),
    success boolean not null default false,
    message varchar(300),
    created_at timestamp not null default current_timestamp
);
