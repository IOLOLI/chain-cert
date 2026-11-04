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

/**
 * 政策参数表的"加宽"迁移（幂等）。
 *
 * <p>背景：计发月数表按国发〔2005〕38 号是 45~70 岁共 26 档，JSON 文本长度约 300 字符，
 * 超过首版 {@code varchar(256)}；建表语句只对新库生效，老库需要在这里把列拓宽到 512。
 *
 * <p>与 {@link SchemaIndexMigration} 同一套路：先查 {@code information_schema.columns}，
 * 只有当前长度不足时才 {@code modify column}；单条失败只记日志、不阻断启动。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class PolicySchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PolicySchemaMigration.class);

    /** 待加宽的列：{表, 列, 目标长度}。 */
    private static final String[][] WIDEN_COLUMNS = {
            {"cs_policy_definition", "default_value", "varchar(512) not null"},
            {"cs_policy_parameter", "param_value", "varchar(512) not null"}
    };

    private final DataSource dataSource;

    public PolicySchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            for (String[] spec : WIDEN_COLUMNS) {
                try {
                    long current = currentLength(connection, spec[0], spec[1]);
                    if (current >= 512 || current < 0) {
                        continue;
                    }
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("alter table " + spec[0] + " modify column "
                                + spec[1] + " " + spec[2]);
                    }
                    log.info("已把 {}.{} 从 varchar({}) 加宽到 varchar(512)", spec[0], spec[1], current);
                } catch (SQLException e) {
                    log.warn("加宽 {}.{} 失败（不影响启动）：{}", spec[0], spec[1], e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.warn("检查政策参数表列宽失败（不影响启动）：{}", e.getMessage());
        }
    }

    /** 列不存在或查不到时返回 -1。 */
    private long currentLength(Connection connection, String table, String column) throws SQLException {
        String sql = "select character_maximum_length from information_schema.columns"
                + " where table_schema = database() and table_name = ? and column_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : -1L;
            }
        }
    }
}
