package com.chainsip.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 给链下索引表补齐查询索引（幂等）。
 *
 * <p><b>为什么需要它</b>：{@code schema.sql} 里除主键与唯一约束外没有二级索引，
 * 而列表类接口的过滤条件都落在这些列上（见 {@code EventController} / {@code AuthService}）：
 * <ul>
 *   <li>{@code /api/events}：按 {@code employee_address} / {@code sponsor_address} / {@code event_name} 过滤，
 *       再按 id 倒序分页；</li>
 *   <li>{@code /api/events/tx}：按 {@code institution_code} / {@code function_name} / {@code success} 过滤；</li>
 *   <li>账号与钱包：按 {@code role} / {@code institution_code} / {@code created_by} / {@code account_id} 查。</li>
 * </ul>
 * 数据量小时看不出差别，一旦上链事件写到十万级，这些查询就是全表扫（EXPLAIN 里 type=ALL），
 * 分页越翻越慢。这里在启动时把缺失的索引补上，老库、新库都能覆盖。
 *
 * <p><b>为什么不写在 schema.sql 里</b>：{@code create table if not exists} 只对新库生效，
 * 而 MySQL 8 不支持 {@code create index if not exists}，写进脚本会在第二次启动时报
 * "Duplicate key name" 直接失败。所以统一放到这里：先查 {@code information_schema.statistics}，
 * 缺了才建，单个索引失败只记日志、不影响启动。
 *
 * <p><b>执行顺序</b>：{@link Ordered#HIGHEST_PRECEDENCE}，早于 {@code DataSeeder} 等写入方。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaIndexMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaIndexMigration.class);

    /**
     * 待补索引：{表名, 索引名, 列定义}。
     *
     * <p>列顺序按"等值过滤在前、范围/排序在后"排，避免建出用不上的索引；
     * 单列索引只加在确实被单独使用的列上（例如 {@code cs_chain_event.event_name}）。
     */
    private static final String[][] INDEXES = {
            // 平台账号：按角色列人、按机构编码找账号、按创建者收口
            {"cs_account", "idx_cs_account_role", "role"},
            {"cs_account", "idx_cs_account_institution", "institution_code"},
            {"cs_account", "idx_cs_account_created_by", "created_by"},

            // 钱包：一个账号可能有多条绑定，列表与"取主钱包"都按 account_id 查
            {"cs_wallet", "idx_cs_wallet_account", "account_id"},

            // 上链留痕：审计页按机构/函数/成功与否过滤，再按 id 倒序
            {"cs_chain_tx_log", "idx_cs_tx_institution", "institution_code"},
            {"cs_chain_tx_log", "idx_cs_tx_function", "function_name"},
            {"cs_chain_tx_log", "idx_cs_tx_success", "success"},
            {"cs_chain_tx_log", "idx_cs_tx_sender", "sender_address"},
            {"cs_chain_tx_log", "idx_cs_tx_created", "created_at"},

            // 链上事件索引：按职工/单位/事件名过滤，另按区块号与时间做报表
            {"cs_chain_event", "idx_cs_event_employee", "employee_address"},
            {"cs_chain_event", "idx_cs_event_sponsor", "sponsor_address"},
            {"cs_chain_event", "idx_cs_event_name", "event_name"},
            {"cs_chain_event", "idx_cs_event_block", "block_number"},
            {"cs_chain_event", "idx_cs_event_created", "created_at"},

            // 登录日志：按账号回溯与按时间清理
            {"cs_login_log", "idx_cs_login_username", "username"},
            {"cs_login_log", "idx_cs_login_created", "created_at"},

            // 政策参数：按 (编码, 地区, 有效期) 取生效版本；到期切换任务按状态扫
            {"cs_policy_parameter", "idx_cs_policy_param_lookup", "param_code, region_code, valid_from, valid_to"},
            {"cs_policy_parameter", "idx_cs_policy_param_status", "status"},

            // 参数审计：按参数编码回溯变更流水（时间倒序由查询的 order by 完成）
            {"cs_policy_audit_log", "idx_cs_policy_audit_code", "param_code, operate_at"},

            // 退休核定单：按职工查、按状态扫待补偿、按交易哈希对账
            {"cs_pension_determination", "idx_cs_determination_employee", "employee_address"},
            {"cs_pension_determination", "idx_cs_determination_status", "status"},
            {"cs_pension_determination", "idx_cs_determination_tx", "determination_tx_hash"}
    };

    private final DataSource dataSource;

    public SchemaIndexMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        int created = 0;
        List<String> failed = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (String[] spec : INDEXES) {
                String table = spec[0];
                String indexName = spec[1];
                String columns = spec[2];
                try {
                    if (indexExists(connection, table, indexName)) {
                        continue;
                    }
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("create index " + indexName + " on " + table + " (" + columns + ")");
                    }
                    created++;
                    log.info("已创建索引 {}({}) on {}", indexName, columns, table);
                } catch (SQLException e) {
                    // 单条失败（例如别的实例刚建好、或列不存在）不影响其余索引
                    failed.add(indexName + ": " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.warn("检查/补齐查询索引失败（不影响启动，但列表类查询可能退化为全表扫描）：{}", e.getMessage());
            return;
        }

        if (created > 0) {
            log.warn("本次启动补齐了 {} 个查询索引（共 {} 个），后续启动会跳过已存在的索引",
                    created, INDEXES.length);
        } else {
            log.debug("查询索引已齐备（共 {} 个）", INDEXES.length);
        }
        if (!failed.isEmpty()) {
            log.warn("有 {} 个索引创建失败：{}", failed.size(), failed);
        }
    }

    /** 只查当前库（{@code database()}）里的索引，避免同名库干扰。 */
    private boolean indexExists(Connection connection, String table, String indexName) throws SQLException {
        String sql = "select count(*) from information_schema.statistics"
                + " where table_schema = database() and table_name = ? and index_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    /** 供测试与文档引用：需要补的索引清单（表名 → 索引名列表）。 */
    public static List<String> indexNames() {
        List<String> names = new ArrayList<>();
        for (String[] spec : INDEXES) {
            names.add(spec[0] + "." + spec[1] + "(" + spec[2] + ")");
        }
        return names;
    }

    static {
        // 触发一次静态校验：索引名里出现重复会导致启动时反复失败，这里只是让问题在开发期就暴露
        List<String> all = new ArrayList<>();
        for (String[] spec : INDEXES) {
            all.add(spec[0] + "." + spec[1]);
        }
        if (all.size() != new java.util.HashSet<>(all).size()) {
            throw new IllegalStateException("SchemaIndexMigration 配置了重复的索引：" + Arrays.toString(all.toArray()));
        }
    }
}
