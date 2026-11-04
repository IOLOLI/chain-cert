package com.chainsip.controller;

import com.chainsip.chain.ChainGateway;
import com.chainsip.chain.dto.BlockInfo;
import com.chainsip.common.ApiResult;
import com.chainsip.config.ChainProperties;
import com.chainsip.config.CryptoProperties;
import com.chainsip.config.JwtProperties;
import com.chainsip.service.RedisCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康检查与启动自检（<b>免登录</b>，挂在 {@code /api/public/**} 白名单下）。
 *
 * <p>为什么需要它：本项目有三个"外部依赖"最容易出问题——MySQL、Redis 与区块链节点。
 * 原来的排查方式是"登录 → 点首页 → 看提示"，一旦登录都进不去就只能翻日志。
 * 有了这个接口，压测脚本、答辩演示、以及任何监控探针都能用一条命令判断"谁挂了"。
 *
 * <p>返回 {@code status} 只有两种：{@code UP}（关键依赖都正常）与 {@code DEGRADED}
 * （数据库或链有问题）。Redis 只是缓存，不可用时只进 {@code warnings}，不参与 {@code status}。
 * <b>不会</b>因为链未就绪就返回 503——本接口本身必须永远可用，否则探针会把"链没起"误判成"服务死了"。
 *
 * <p>{@code warnings} 里会列出"能用但不该上生产"的配置，例如仍在使用仓库里的默认 JWT 密钥、
 * 默认主密钥、以及联调用的代签接口没关。这一项是给部署者看的，不返回任何密钥内容。
 */
@Tag(name = "01 健康检查", description = "数据库 / Redis / 链网关状态 / 运行时长 / 配置告警（免登录）")
@RestController
@RequestMapping("/api/public")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    /** 与 application.yml 中的默认值对应：出现即说明没改过，需要提醒部署者。 */
    private static final String DEV_JWT_SECRET_MARK = "chainsip-dev-jwt-secret";
    private static final String DEV_MASTER_KEY_MARK = "chainsip-dev-master-key";

    private final DataSource dataSource;
    private final ChainGateway chainGateway;
    private final ChainProperties chainProperties;
    private final JwtProperties jwtProperties;
    private final CryptoProperties cryptoProperties;
    private final RedisCacheService redisCacheService;
    private final RedisProperties redisProperties;

    public HealthController(DataSource dataSource, ChainGateway chainGateway,
                            ChainProperties chainProperties, JwtProperties jwtProperties,
                            CryptoProperties cryptoProperties, RedisCacheService redisCacheService,
                            RedisProperties redisProperties) {
        this.dataSource = dataSource;
        this.chainGateway = chainGateway;
        this.chainProperties = chainProperties;
        this.jwtProperties = jwtProperties;
        this.cryptoProperties = cryptoProperties;
        this.redisCacheService = redisCacheService;
        this.redisProperties = redisProperties;
    }

    @Operation(summary = "健康检查", description = "免登录；返回数据库、Redis、链网关、运行时长与配置告警")
    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        data.put("service", "chainsip-backend");
        data.put("version", resolveVersion());
        data.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        data.put("timestamp", java.time.LocalDateTime.now().toString());

        boolean dbOk = checkDatabase(data, warnings);
        boolean chainOk = checkChain(data, warnings);
        checkRedis(data, warnings);
        checkConfig(warnings);

        data.put("memory", memory());
        data.put("warnings", warnings);
        // status 只看"关键依赖能不能用"：
        //   数据库挂了 = 本服务真的不可用；
        //   链挂了 = 认证/账号/索引仍可用，但上链业务不可用，所以也降级。
        // 配置类提醒（仍是开发默认密钥等）只进 warnings，不参与 status ——
        // 否则一套功能完全正常的本地环境会永远显示 DEGRADED，探针也就失去意义。
        data.put("status", (dbOk && chainOk) ? "UP" : "DEGRADED");

        return ApiResult.ok(data);
    }

    /** Redis：只做 PING。不可用时只告警，不把整个服务判成 DEGRADED（缓存可以跳过）。 */
    private void checkRedis(Map<String, Object> data, List<String> warnings) {
        Map<String, Object> redis = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        boolean ok = redisCacheService.ping();
        redis.put("ok", ok);
        redis.put("latencyMs", System.currentTimeMillis() - start);
        redis.put("host", redisProperties.getHost());
        redis.put("port", redisProperties.getPort());
        redis.put("database", redisProperties.getDatabase());
        redis.put("keyPrefix", RedisCacheService.KEY_PREFIX);
        if (!ok) {
            redis.put("message", "Redis 不可用，缓存已自动跳过（不影响核心业务）");
            warnings.add("Redis 不可用：缓存已跳过，请确认 Redis 已启动（本机 Docker 示例：docker start chainsip-redis）");
        }
        data.put("redis", redis);
    }

    /** 数据库：连一下、跑一条最小查询并计时。连不上是本服务真正不可用。 */
    private boolean checkDatabase(Map<String, Object> data, List<String> warnings) {
        Map<String, Object> db = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select 1")) {
            resultSet.next();
            db.put("ok", true);
            db.put("latencyMs", System.currentTimeMillis() - start);
            db.put("product", connection.getMetaData().getDatabaseProductVersion());
        } catch (Exception e) {
            db.put("ok", false);
            db.put("latencyMs", System.currentTimeMillis() - start);
            db.put("error", e.getMessage());
            warnings.add("数据库不可用：" + e.getMessage());
            log.warn("健康检查：数据库不可用", e);
        }
        data.put("db", db);
        return Boolean.TRUE.equals(db.get("ok"));
    }

    /**
     * 链：只读链网关的就绪状态与最新区块号。
     * 未就绪时不抛异常（否则整条健康检查都没了），把网关给出的排查提示原样带出去。
     */
    private boolean checkChain(Map<String, Object> data, List<String> warnings) {
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("enabled", chainProperties.isEnabled());
        boolean ready = chainGateway.isReady();
        chain.put("ready", ready);
        chain.put("groupId", chainProperties.getGroupId());
        chain.put("peers", chainProperties.getPeers());
        chain.put("coreContract", chainProperties.getContracts().getCore());
        chain.put("transferContract", chainProperties.getContracts().getTransfer());

        if (ready) {
            try {
                BlockInfo block = chainGateway.latestBlock();
                if (block != null) {
                    chain.put("blockNumber", block.getBlockNumber());
                }
            } catch (Exception e) {
                // 就绪但读块失败：属于链侧抖动，降级处理而不是让接口报错
                chain.put("ready", false);
                chain.put("message", "链已连接但读区块失败：" + e.getMessage());
                ready = false;
            }
        } else {
            String message = chainGateway.statusMessage();
            chain.put("message", message);
            if (chainProperties.isEnabled()) {
                warnings.add("链网关未就绪：" + message);
            }
        }
        data.put("chain", chain);
        return ready;
    }

    /** 配置体检：只判断"是不是还用着仓库里的默认值"，绝不回显密钥内容。 */
    private void checkConfig(List<String> warnings) {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.contains(DEV_JWT_SECRET_MARK)) {
            warnings.add("JWT 密钥仍是仓库默认值，生产环境必须用 CHAINSIP_JWT_SECRET 覆盖");
        }
        String masterKey = cryptoProperties.getMasterKey();
        if (masterKey == null || masterKey.contains(DEV_MASTER_KEY_MARK)) {
            warnings.add("机构私钥加密主密钥仍是仓库默认值，生产环境必须用 CHAINSIP_CRYPTO_MASTER_KEY 覆盖");
        }
        if (cryptoProperties.isAllowDemoSign()) {
            warnings.add("联调代签接口 /api/auth/demo/sign 处于开启状态，生产环境应把 chainsip.crypto.allow-demo-sign 置 false");
        }
        // 注意：这里不检查"是否配置了 ADMIN 机构私钥"。ADMIN 私钥的正常位置是库里的
        // cs_institution_key（创建账号时自动生成并加密落库），配置项只是"接管某个已存在账户"的可选用法，
        // 空的完全正常 —— 把它当告警会让每套环境都常亮一条无用提示。
    }

    private Map<String, Object> memory() {
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long max = runtime.maxMemory() / (1024 * 1024);
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("usedMb", used);
        memory.put("maxMb", max);
        memory.put("usagePercent", max > 0 ? Math.round(used * 100.0 / max) : 0);
        return memory;
    }

    private String resolveVersion() {
        String version = HealthController.class.getPackage() == null
                ? null : HealthController.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
