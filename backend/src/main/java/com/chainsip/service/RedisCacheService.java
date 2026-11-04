package com.chainsip.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 缓存门面：统一加项目前缀、统一异常降级。
 *
 * <p>Redis 在本项目里是可选的加速层：连不上时所有缓存操作直接跳过，
 * 业务继续走数据库/链，不会因为缓存故障而失败。失败后 30 秒内不再重试，
 * 避免 Redis 未启动时每个请求都等一次连接超时。
 */
@Service
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    /** 所有 ChainSIP 键的统一前缀，便于和同机其他项目区分。 */
    public static final String KEY_PREFIX = "chainsip:";

    private static final long RETRY_BACKOFF_MILLIS = 30_000L;
    private static final long WARN_INTERVAL_MILLIS = 60_000L;
    private static final long LOCK_WAIT_MILLIS = 500L;
    private static final Duration LOAD_LOCK_TTL = Duration.ofSeconds(10);

    /** 只有 token 匹配时才删除，避免释放掉别人续期后的锁。 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** 计数 + 首次设置过期时间，保证限流窗口不会无限增长。 */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('incr', KEYS[1]) "
                    + "if current == 1 then redis.call('pexpire', KEYS[1], ARGV[1]) end "
                    + "return current",
            Long.class);

    /**
     * 缓存未命中时，同一进程内只允许一个线程回源。
     * 用固定数量的分段锁而不是按 key 建锁表：热点 key 再多也不会无限增长内存；
     * 不同 key 偶尔落在同一段只会短暂串行，不影响正确性。
     */
    private static final int LOCAL_LOCK_STRIPES = 64;
    private final ReentrantLock[] localLocks = new ReentrantLock[LOCAL_LOCK_STRIPES];

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final AtomicLong lastWarnAt = new AtomicLong(0L);
    private volatile long retryAfter;
    private volatile boolean available = true;

    /**
     * 远程锁的获取结果。缓存降级和"必须互斥"是两种语义，不能都用 boolean 混在一起：
     * <ul>
     *   <li>{@link #ACQUIRED}：拿到了锁；</li>
     *   <li>{@link #BUSY}：锁被别人持有（调用方决定等还是拒绝）；</li>
     *   <li>{@link #UNAVAILABLE}：Redis 不可用，调用方按 fail-open 处理。</li>
     * </ul>
     */
    public enum LockResult {
        ACQUIRED,
        BUSY,
        UNAVAILABLE
    }

    public RedisCacheService(RedisTemplate<String, Object> redisTemplate,
                             StringRedisTemplate stringRedisTemplate) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        for (int i = 0; i < localLocks.length; i++) {
            localLocks[i] = new ReentrantLock();
        }
    }

    public Optional<Object> get(String key) {
        if (isBackingOff()) {
            return Optional.empty();
        }
        try {
            Object value = redisTemplate.opsForValue().get(KEY_PREFIX + key);
            markAvailable();
            return Optional.ofNullable(value);
        } catch (RuntimeException e) {
            markUnavailable(e);
            return Optional.empty();
        }
    }

    public boolean put(String key, Object value, Duration ttl) {
        if (isBackingOff()) {
            return false;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + key, value, ttl);
            markAvailable();
            return true;
        } catch (RuntimeException e) {
            markUnavailable(e);
            return false;
        }
    }

    public boolean delete(String key) {
        if (isBackingOff()) {
            return false;
        }
        try {
            Boolean removed = redisTemplate.delete(KEY_PREFIX + key);
            markAvailable();
            return Boolean.TRUE.equals(removed);
        } catch (RuntimeException e) {
            markUnavailable(e);
            return false;
        }
    }

    /**
     * 读缓存；未命中时只让一个线程回源，其余线程等待并复用结果。
     *
     * <p>这是缓存击穿的主要防线：辖区总览、监管大屏这类接口一次回源要打很多次链，
     * 如果并发未命中时每个请求都自己去读链，缓存等于没加。
     *
     * <p>多实例场景下再叠加一把短 TTL 的 Redis 锁；拿不到锁的实例先短暂轮询缓存，
     * 仍拿不到就自行回源（fail-open），避免一个实例异常把其他实例全部拖住。
     */
    public <T> T getOrLoad(String key, Duration ttl, Supplier<T> loader) {
        Optional<Object> cached = get(key);
        if (cached.isPresent()) {
            return cast(cached.get());
        }

        ReentrantLock localLock = localLocks[Math.floorMod(key.hashCode(), LOCAL_LOCK_STRIPES)];
        localLock.lock();
        try {
            cached = get(key);
            if (cached.isPresent()) {
                return cast(cached.get());
            }

            String lockKey = "load:" + key;
            String token = UUID.randomUUID().toString();
            LockResult remote = tryLock(lockKey, token, LOAD_LOCK_TTL);
            boolean ownsRemoteLock = remote == LockResult.ACQUIRED;
            try {
                if (remote == LockResult.BUSY) {
                    long deadline = System.currentTimeMillis() + LOCK_WAIT_MILLIS;
                    while (System.currentTimeMillis() < deadline) {
                        sleepQuietly(50L);
                        cached = get(key);
                        if (cached.isPresent()) {
                            return cast(cached.get());
                        }
                    }
                }

                T value = loader.get();
                if (value != null) {
                    put(key, value, ttl);
                }
                return value;
            } finally {
                if (ownsRemoteLock) {
                    releaseLock(lockKey, token);
                }
            }
        } finally {
            localLock.unlock();
        }
    }

    /**
     * 原子获取一把带过期的锁。
     *
     * <p>Redis 不可用时返回 {@link LockResult#UNAVAILABLE}，由调用方决定 fail-open 还是拒绝；
     * 本方法不替业务做"Redis 挂了要不要继续"的决定。
     */
    public LockResult tryLock(String key, String token, Duration ttl) {
        if (isBackingOff()) {
            return LockResult.UNAVAILABLE;
        }
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + key, token, ttl);
            markAvailable();
            return Boolean.TRUE.equals(acquired) ? LockResult.ACQUIRED : LockResult.BUSY;
        } catch (RuntimeException e) {
            markUnavailable(e);
            return LockResult.UNAVAILABLE;
        }
    }

    /** 释放锁；只有 token 仍是自己写入的值时才删除。 */
    public boolean releaseLock(String key, String token) {
        try {
            Long removed = stringRedisTemplate.execute(
                    UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + key), token);
            return removed != null && removed > 0;
        } catch (RuntimeException e) {
            markUnavailable(e);
            return false;
        }
    }

    /**
     * 原子自增并确保首次写入时设置 TTL，用于限流和轻量计数器。
     *
     * @return 自增后的值；Redis 不可用时返回 {@code null}（调用方应按 fail-open 处理）
     */
    public Long increment(String key, Duration ttl) {
        if (isBackingOff()) {
            return null;
        }
        try {
            Long value = stringRedisTemplate.execute(
                    INCR_WITH_TTL_SCRIPT,
                    Collections.singletonList(KEY_PREFIX + key),
                    String.valueOf(ttl.toMillis()));
            markAvailable();
            return value;
        } catch (RuntimeException e) {
            markUnavailable(e);
            return null;
        }
    }

    /**
     * 按前缀删除缓存，供管理端修改数据后主动失效。
     *
     * <p>使用 SCAN 而不是 KEYS，避免大库上一次性阻塞 Redis。
     */
    public long deleteByPrefix(String prefix) {
        if (isBackingOff()) {
            return 0L;
        }
        String match = KEY_PREFIX + prefix + "*";
        Set<String> keys = new LinkedHashSet<>();
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                try (Cursor<byte[]> cursor = connection.scan(
                        ScanOptions.scanOptions().match(match).count(500).build())) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                }
                return null;
            });
            if (keys.isEmpty()) {
                markAvailable();
                return 0L;
            }
            Long removed = redisTemplate.delete(keys);
            markAvailable();
            return removed == null ? 0L : removed;
        } catch (RuntimeException e) {
            markUnavailable(e);
            return 0L;
        }
    }

    /** Redis 当前是否可用（处于退避期时视为不可用，避免每个请求都等超时）。 */
    public boolean isAvailable() {
        return available && !isBackingOff();
    }

    /** 只做 PING，供健康检查使用；即使处于退避期也会真正探测一次，便于发现恢复。 */
    public boolean ping() {
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            boolean ok = "PONG".equalsIgnoreCase(pong);
            if (ok) {
                markAvailable();
            } else {
                markUnavailable(null);
            }
            return ok;
        } catch (RuntimeException e) {
            markUnavailable(e);
            return false;
        }
    }

    /** 启动自检：真正 SET/GET/DELETE 一次，确认不仅能连通，也有读写权限。 */
    public boolean probe() {
        String key = KEY_PREFIX + "healthcheck";
        try {
            redisTemplate.opsForValue().set(key, "ok", Duration.ofSeconds(30));
            Object value = redisTemplate.opsForValue().get(key);
            redisTemplate.delete(key);
            boolean ok = "ok".equals(value);
            if (ok) {
                markAvailable();
            } else {
                markUnavailable(null);
            }
            return ok;
        } catch (RuntimeException e) {
            markUnavailable(e);
            return false;
        }
    }

    private boolean isBackingOff() {
        return System.currentTimeMillis() < retryAfter;
    }

    private void markAvailable() {
        retryAfter = 0L;
        if (!available) {
            available = true;
            log.info("Redis 已恢复，缓存重新启用");
        }
    }

    private void markUnavailable(RuntimeException error) {
        available = false;
        retryAfter = System.currentTimeMillis() + RETRY_BACKOFF_MILLIS;

        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= WARN_INTERVAL_MILLIS && lastWarnAt.compareAndSet(last, now)) {
            log.warn("Redis 不可用，缓存操作将跳过 {} 秒{}",
                    RETRY_BACKOFF_MILLIS / 1000,
                    error == null ? "" : "：" + error.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
