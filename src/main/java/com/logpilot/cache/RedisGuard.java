package com.logpilot.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 访问的唯一入口。
 *
 * 为什么所有 Redis 操作都要包一层：
 *   Redis 是"增强"而不是"依赖"。它挂了，日志照样要能入库、分析照样要能出结果。
 *   所以这里把异常全部吃掉，并且用了一个很轻的熔断：失败后 30 秒内不再重试，
 *   避免每个请求都去等一次连接超时。
 *
 * 返回值约定（很重要）：
 *   - markIfAbsent 失败时返回 true  -> 当作"没见过"，宁可多存也不丢日志
 *   - tryLock      失败时返回 true  -> 当作"拿到锁"，宁可多做一次也不卡住用户
 */
@Component
public class RedisGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisGuard.class);
    private static final long RETRY_AFTER_MS = 30_000L;

    private final StringRedisTemplate redis;
    private final AtomicLong lastFailureAt = new AtomicLong(0);
    private volatile boolean everConnected = false;

    public RedisGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isAvailable() {
        return everConnected && !inBackoff();
    }

    private boolean inBackoff() {
        long last = lastFailureAt.get();
        return last > 0 && System.currentTimeMillis() - last < RETRY_AFTER_MS;
    }

    /**
     * 设置一个不存在的 key。返回 true 表示"是我设置的"（首次出现）。
     * 用于日志指纹去重：同一个指纹在窗口期内只认第一次。
     */
    public boolean markIfAbsent(String key, Duration ttl) {
        if (inBackoff()) {
            return true;
        }
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, "1", ttl);
            everConnected = true;
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            markFailure(e);
            return true;
        }
    }

    /** 简易分布式锁，SET NX PX。释放时按 key 删除即可（单机/演示场景够用）。 */
    public boolean tryLock(String key, Duration ttl) {
        return markIfAbsent(key, ttl);
    }

    public void release(String key) {
        if (inBackoff()) {
            return;
        }
        try {
            redis.delete(key);
        } catch (Exception e) {
            markFailure(e);
        }
    }

    public Optional<String> get(String key) {
        if (inBackoff()) {
            return Optional.empty();
        }
        try {
            String v = redis.opsForValue().get(key);
            everConnected = true;
            return Optional.ofNullable(v);
        } catch (Exception e) {
            markFailure(e);
            return Optional.empty();
        }
    }

    public void set(String key, String value, Duration ttl) {
        if (inBackoff()) {
            return;
        }
        try {
            redis.opsForValue().set(key, value, ttl);
            everConnected = true;
        } catch (Exception e) {
            markFailure(e);
        }
    }

    private void markFailure(Exception e) {
        long now = System.currentTimeMillis();
        long last = lastFailureAt.get();
        boolean firstAfterSuccess = last == 0 || now - last >= RETRY_AFTER_MS;
        lastFailureAt.set(now);
        if (firstAfterSuccess) {
            log.warn("Redis 不可用，进入降级模式（{} 秒内不重试）: {}", RETRY_AFTER_MS / 1000, e.toString());
        }
    }
}
