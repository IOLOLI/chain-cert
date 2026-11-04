package com.chainsip.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 启动自检：在 Spring 容器刷新（也就是 schema.sql 初始化）之前，先真正连一次数据库。
 *
 * <p>为什么要在这么早的阶段做：项目现在只支持 MySQL，若 MySQL 没启动，默认的失败方式是在
 * schema 初始化阶段抛一个 HikariPool 超时堆栈，信息很长但不直观。这里提前用
 * {@code java.sql.DriverManager} 试连一次，失败时打出一行明确的中文提示，
 * 并且<b>继续把异常抛出去</b>（不吞异常）——数据库不可用属于致命配置错误，
 * 让它以清晰的失败告终，比带着一个连不上库的进程继续跑更安全。
 *
 * <p>监听的是 {@link ApplicationEnvironmentPreparedEvent}（发生在 application.yml 已经加载完成、
 * 但任何 Bean 都还没创建的时刻），因此读到的就是最终生效的 datasource 配置。
 */
public class DataSourceStartupCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(DataSourceStartupCheck.class);

    /** 连接超时（毫秒）：MySQL 没启动时 TCP 会立刻被拒，但主机写错时可能长时间悬挂，故显式限制。 */
    private static final String CONNECT_TIMEOUT_MILLIS = "3000";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment env = event.getEnvironment();

        String url = env.getProperty("spring.datasource.url");
        if (url == null || url.trim().isEmpty()) {
            String message = "未配置数据源（spring.datasource.url）：本项目只支持 MySQL，"
                    + "请在 application.yml 里填写 jdbc:mysql://127.0.0.1:3306/chainsip";
            log.error(message);
            throw new IllegalStateException(message);
        }
        url = url.trim();

        if (!url.startsWith("jdbc:")) {
            log.warn("spring.datasource.url 不是 JDBC 形式，跳过启动自检：{}", url);
            return;
        }

        String username = env.getProperty("spring.datasource.username", "");
        String password = env.getProperty("spring.datasource.password", "");
        String driverClass = env.getProperty("spring.datasource.driver-class-name");
        String database = databaseNameOf(url);

        // Spring Boot 会在首次取连接时才加载驱动；这里先显式加载，好把"驱动缺失"与"连不上"区分开
        if (driverClass != null && !driverClass.trim().isEmpty()) {
            try {
                Class.forName(driverClass.trim());
            } catch (ClassNotFoundException e) {
                String message = "数据库驱动不存在（" + driverClass + "）：请确认 pom.xml 里的 mysql-connector-j 依赖没有被移除";
                log.error(message);
                throw new IllegalStateException(message, e);
            }
        }

        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        props.setProperty("connectTimeout", CONNECT_TIMEOUT_MILLIS);
        props.setProperty("socketTimeout", "5000");

        try (Connection connection = DriverManager.getConnection(url, props)) {
            log.info("数据库连接自检通过：{}（库 = {}，用户 = {}）", url, connection.getCatalog(), username);
        } catch (SQLException e) {
            String message = "无法连接 MySQL（" + url + "）：请确认 MySQL 已启动且库 "
                    + database + " 已创建"
                    + (isUnknownDatabase(e) ? "（该库当前不存在，请先执行：CREATE DATABASE " + database
                    + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;）" : "")
                    + "；底层错误：" + e.getErrorCode() + "/" + e.getSQLState() + " " + e.getMessage();
            log.error("================================================================");
            log.error(message);
            log.error("================================================================");
            throw new IllegalStateException(message, e);
        }
    }

    /** 从 JDBC URL 里取出库名，仅用于提示信息（取不到就原样返回整串）。 */
    private String databaseNameOf(String url) {
        String withoutQuery = url;
        int query = withoutQuery.indexOf('?');
        if (query >= 0) {
            withoutQuery = withoutQuery.substring(0, query);
        }
        int slash = withoutQuery.lastIndexOf('/');
        if (slash < 0 || slash == withoutQuery.length() - 1) {
            return url;
        }
        return withoutQuery.substring(slash + 1);
    }

    private boolean isUnknownDatabase(SQLException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("unknown database");
    }
}
