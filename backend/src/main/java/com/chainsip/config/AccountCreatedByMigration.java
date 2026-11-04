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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 给 {@code cs_account} 表补齐并回填 {@code created_by}（创建者账号 id）。
 *
 * <p><b>两件事</b>：
 * <ol>
 *   <li><b>幂等补列</b>：{@code schema.sql} 只有 {@code create table if not exists}，老库不会自动多出这一列，
 *       这里先查 {@code information_schema} 再决定是否 {@code alter table}（MySQL 8 不支持
 *       {@code ADD COLUMN IF NOT EXISTS}，做法与 {@link WalletSchemaMigration} 一致）；</li>
 *   <li><b>一次性回填</b>：{@code created_by} 是后加的列，补列之前建的账号全是 {@code NULL}，
 *       会让"缴费单位只显示本单位职工"的收口把用户<b>自己刚建的职工</b>也挡掉。
 *       这里从该账号机构密钥的备注（{@code cs_institution_key.remark}）反推创建者用户名，再换成账号 id 写回。</li>
 * </ol>
 *
 * <p><b>回填算法</b>：遍历 {@code created_by is null} 的账号 → 用它的 {@code institution_code} 取 remark →
 * 正则 {@code (?:由|（|()\s*(X)\s*创建} 抠出 X（现有 remark 形如
 * {@code 由 bureau_demo 创建，服务城市：QA?}、{@code 由 sponsor_demo 创建}，
 * 兼容 {@code （admin 创建，机构编码 …）} 这种"钱包备注"写法）→
 * {@code admin} 一律解析成 admin 账号 id（{@code ADMIN 私钥由配置注入} 那条同义）→
 * 其它用户名按 {@code cs_account.username} 查 id。<b>推断不出来就保持 NULL</b>（不瞎猜、不报错）。
 *
 * <p><b>执行顺序</b>：{@link Ordered#HIGHEST_PRECEDENCE}，早于 {@link AdminKeyBootstrap} 与
 * {@link com.chainsip.service.DataSeeder}。整个流程可重复执行：第二次跑通常只会得到"回填 0 条"。
 * 任何 SQL 失败只记日志、不阻断启动。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccountCreatedByMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountCreatedByMigration.class);

    private static final String TABLE = "cs_account";
    private static final String COLUMN = "created_by";

    /** 从机构密钥备注里抠"创建者用户名"：`由 X 创建` 与 `（X 创建` / `(X 创建` 都认。 */
    private static final Pattern CREATOR = Pattern.compile("(?:由|（|\\()\\s*([^\\s，,（()]+)\\s*创建");

    /** ADMIN 自己那条机构密钥的备注前缀：由配置注入，创建者就是 admin。 */
    private static final String ADMIN_KEY_MARK = "admin-private-key";

    private static final String ADMIN_USERNAME = "admin";

    private final DataSource dataSource;

    public AccountCreatedByMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            ensureColumn(connection);
            backfillCreatedBy(connection);
        } catch (SQLException e) {
            log.warn("检查/补齐 {}.{} 列失败（不影响启动，但账号列表的“创建归属”判定会退化为只按链上归属）：{}",
                    TABLE, COLUMN, e.getMessage());
        }
    }

    private void ensureColumn(Connection connection) throws SQLException {
        if (columnExists(connection)) {
            log.debug("{}.{} 列已存在，无需补列", TABLE, COLUMN);
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("alter table " + TABLE + " add column " + COLUMN + " bigint null");
        }
        log.warn("已为 {} 表补齐 {} 列（记录创建者账号 id，用于机构账号列表按登录者范围收口）", TABLE, COLUMN);
    }

    /**
     * 把 {@code created_by is null} 的账号按机构密钥备注回填创建者 id。
     *
     * <p>幂等：跑完一轮后，能推断的都写上了；剩下仍是 NULL 的是"根本推不出来"的（例如 {@code （自助注册职工）}），
     * 下次再跑只会重复得到"回填 0 条"。
     */
    private void backfillCreatedBy(Connection connection) throws SQLException {
        List<Pending> pending = selectPending(connection);
        if (pending.isEmpty()) {
            log.info("created_by 回填：没有待处理账号（created_by 均已写入），回填 0 条，无法推断 0 条");
            return;
        }
        Long adminId = findAccountId(connection, ADMIN_USERNAME);
        int filled = 0;
        int unknown = 0;
        for (Pending row : pending) {
            String creatorUsername = inferCreatorUsername(connection, row.institutionCode);
            Long creatorId = resolveCreatorId(connection, creatorUsername, adminId);
            // 推不出创建者、或推出来就是自己：两种都保持 NULL（不瞎猜）
            if (creatorId == null || creatorId.equals(row.id)) {
                unknown++;
                log.debug("created_by 无法推断，保持 NULL：accountId={}, username={}, institutionCode={}, 推断创建者={}",
                        row.id, row.username, row.institutionCode, creatorUsername);
                continue;
            }
            updateCreatedBy(connection, row.id, creatorId);
            filled++;
            log.debug("created_by 回填：accountId={}, username={} -> createdBy={}（创建者 {}）",
                    row.id, row.username, creatorId, creatorUsername);
        }
        log.info("created_by 回填完成：回填 {} 条，无法推断 {} 条（无法推断的保持 NULL，不影响启动）",
                filled, unknown);
    }

    /** 从该账号机构密钥的备注里推断创建者用户名；推不出来返回 null。 */
    private String inferCreatorUsername(Connection connection, String institutionCode) throws SQLException {
        if (institutionCode == null || institutionCode.trim().isEmpty()) {
            return null;
        }
        String remark = selectInstitutionRemark(connection, institutionCode.trim());
        if (remark == null || remark.trim().isEmpty()) {
            return null;
        }
        Matcher matcher = CREATOR.matcher(remark);
        if (matcher.find()) {
            String creator = matcher.group(1).trim();
            return creator.isEmpty() ? null : creator;
        }
        // ADMIN 自己的机构密钥由配置注入，创建者就是 admin
        if (remark.contains(ADMIN_KEY_MARK) || remark.contains("ADMIN 私钥由配置注入")) {
            return ADMIN_USERNAME;
        }
        return null;
    }

    /** 创建者用户名 → 账号 id；{@code admin} 统一走 admin 账号 id。 */
    private Long resolveCreatorId(Connection connection, String creatorUsername, Long adminId) throws SQLException {
        if (creatorUsername == null || creatorUsername.trim().isEmpty()) {
            return null;
        }
        String creator = creatorUsername.trim();
        if (ADMIN_USERNAME.equalsIgnoreCase(creator)) {
            return adminId;
        }
        return findAccountId(connection, creator);
    }

    private List<Pending> selectPending(Connection connection) throws SQLException {
        List<Pending> rows = new ArrayList<>();
        String sql = "select id, username, institution_code from " + TABLE
                + " where " + COLUMN + " is null order by id";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                Pending row = new Pending();
                row.id = resultSet.getLong("id");
                row.username = resultSet.getString("username");
                row.institutionCode = resultSet.getString("institution_code");
                rows.add(row);
            }
        }
        return rows;
    }

    private String selectInstitutionRemark(Connection connection, String institutionCode) throws SQLException {
        String sql = "select remark from cs_institution_key where code = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, institutionCode);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("remark") : null;
            }
        }
    }

    private Long findAccountId(Connection connection, String username) throws SQLException {
        String sql = "select id from " + TABLE + " where username = ? order by id limit 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong("id") : null;
            }
        }
    }

    private void updateCreatedBy(Connection connection, long accountId, long creatorId) throws SQLException {
        String sql = "update " + TABLE + " set " + COLUMN + " = ? where id = ? and " + COLUMN + " is null";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, creatorId);
            statement.setLong(2, accountId);
            statement.executeUpdate();
        }
    }

    /** 只查当前库（{@code database()}）里的这一列，避免同名库干扰。 */
    private boolean columnExists(Connection connection) throws SQLException {
        String sql = "select count(*) from information_schema.columns"
                + " where table_schema = database() and table_name = ? and column_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, TABLE);
            statement.setString(2, COLUMN);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    /** 待回填的一行。 */
    private static final class Pending {
        private long id;
        private String username;
        private String institutionCode;
    }
}
