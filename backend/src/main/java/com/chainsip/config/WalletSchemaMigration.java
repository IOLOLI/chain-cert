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
 * 给已存在的 {@code cs_wallet} 表幂等补列：{@code remark}（绑定来源备注）。
 *
 * <p><b>为什么需要它</b>：{@code schema.sql} 只包含 {@code create table if not exists}，
 * 表已经存在时它不会新增列。而"创建账号时自动绑定地址"需要在绑定记录里写清来源，
 * 老库因此必须补一列。MySQL 8 不支持 {@code ADD COLUMN IF NOT EXISTS}，
 * 所以这里先查 {@code information_schema} 再决定是否执行 {@code alter table}。
 *
 * <p><b>执行顺序</b>：{@link Ordered#HIGHEST_PRECEDENCE}，早于 {@link AdminKeyBootstrap} 与
 * {@link com.chainsip.service.DataSeeder}——它们都会写入 {@code cs_wallet}，
 * 必须等这一列就位。DDL 失败只记日志、不阻断启动（与项目里"链上/附加能力失败不影响主流程"一致）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WalletSchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WalletSchemaMigration.class);

    private static final String TABLE = "cs_wallet";
    private static final String COLUMN = "remark";

    private final DataSource dataSource;

    public WalletSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            if (columnExists(connection)) {
                log.debug("{}.{} 列已存在，无需补列", TABLE, COLUMN);
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("alter table " + TABLE + " add column " + COLUMN + " varchar(300) null");
            }
            log.warn("已为 {} 表补齐 {} 列（记录地址绑定来源）", TABLE, COLUMN);
        } catch (SQLException e) {
            log.warn("检查/补齐 {}.{} 列失败（不影响启动，但自动绑定地址的来源备注可能写不进去）：{}",
                    TABLE, COLUMN, e.getMessage());
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
}
