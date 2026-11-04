package com.chainsip;

import com.chainsip.config.ChainProperties;
import com.chainsip.config.DataSourceStartupCheck;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ChainSIP 区块链养老保险平台 —— 后端服务入口。
 *
 * <p>分层约定（与合约的职责切分保持一致）：
 * <ul>
 *   <li>认证在链下：账号、密码、地址绑定、会话全部在本服务的数据库里；</li>
 *   <li>授权在链上：角色 / 账户 / 资金 / 转移状态以合约 {@code roleOf(address)} 等为准，
 *       本服务只做"挑选正确的机构私钥去发交易"，权限最终由合约裁决。</li>
 * </ul>
 *
 * <p>数据源固定为 MySQL 8（H2 已移除）。{@link DataSourceStartupCheck} 会在容器刷新前先连一次库，
 * 连不上时给出明确的一行中文提示并终止启动。
 */
@SpringBootApplication
@EnableScheduling
// 政策参数 Mapper 单独放在 com.chainsip.policy.mapper，这里与既有 mapper 包一起扫描
@MapperScan({"com.chainsip.mapper", "com.chainsip.policy.mapper"})
@EnableConfigurationProperties(ChainProperties.class)
public class ChainSipApplication {

    private static final Logger log = LoggerFactory.getLogger(ChainSipApplication.class);

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ChainSipApplication.class);
        // 必须在 run() 之前挂上：该监听器处理的是 ApplicationEnvironmentPreparedEvent，
        // 也就是"配置已加载、Bean 尚未创建、schema.sql 尚未执行"的时刻。
        application.addListeners(new DataSourceStartupCheck());

        ConfigurableApplicationContext context = application.run(args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8080");
        String ctx = env.getProperty("server.servlet.context-path", "");
        log.info("ChainSIP 后端已启动：http://127.0.0.1:{}{}", port, ctx);
        log.info("接口文档：http://127.0.0.1:{}{}/swagger-ui/index.html", port, ctx);
        log.info("数据源：{}", env.getProperty("spring.datasource.url"));
    }
}
