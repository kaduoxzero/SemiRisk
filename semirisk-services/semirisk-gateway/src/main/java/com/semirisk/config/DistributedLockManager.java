package com.semirisk.config;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 分布式锁管理器（Phase 2）。
 * 基于 Redisson RLock，当 Redis 不可用时自动降级为本地 ReentrantLock。
 */
@Component
public class DistributedLockManager {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockManager.class);
    private static final String LOCK_PREFIX = "semirisk:lock:";

    private final RedissonClient redissonClient;
    private final boolean distributedEnabled;
    /** 本地锁回退：lockName → ReentrantLock */
    private final ConcurrentHashMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    private final org.springframework.beans.factory.ObjectProvider<RedissonClient> redissonProvider;

    @Autowired
    public DistributedLockManager(org.springframework.beans.factory.ObjectProvider<RedissonClient> redissonProvider) {
        this.redissonProvider = redissonProvider;
        this.redissonClient = redissonProvider.getIfAvailable();
        this.distributedEnabled = redissonClient != null;
        if (distributedEnabled) {
            log.info("DistributedLockManager: Redisson distributed lock enabled");
        } else {
            log.warn("DistributedLockManager: Redisson not available, falling back to local locks");
        }
    }

    /**
     * 尝试获取分布式锁并执行任务。
     *
     * @param lockName 锁名称（唯一标识）
     * @param waitMs   等待锁的最大毫秒数
     * @param leaseMs  锁持有时间（自动释放），0 表示不自动释放
     * @param task     获取锁后执行的任务
     * @return 任务返回值，获取锁失败则返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T executeWithLock(String lockName, long waitMs, long leaseMs, Supplier<T> task) {
        String key = LOCK_PREFIX + lockName;
        if (distributedEnabled && redissonClient != null) {
            try {
                RLock lock = redissonClient.getLock(key);
                boolean acquired = lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS);
                if (!acquired) {
                    log.debug("Failed to acquire distributed lock: {}", lockName);
                    return null;
                }
                return task.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for lock: {}", lockName);
                return null;
            } catch (Exception e) {
                log.error("Redisson lock error, falling back to local lock for: {}", lockName, e);
            }
        }
        // Local lock fallback
        ReentrantLock localLock = localLocks.computeIfAbsent(key, k -> new ReentrantLock());
        try {
            if (!localLock.tryLock(waitMs, TimeUnit.MILLISECONDS)) {
                return null;
            }
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (localLock.isHeldByCurrentThread()) {
                localLock.unlock();
            }
        }
    }

    /**
     * 简化版：不带超时，执行任务。
     */
    public void executeWithLockSimple(String lockName, Runnable task) {
        String key = LOCK_PREFIX + lockName;
        if (distributedEnabled && redissonClient != null) {
            try {
                RLock lock = redissonClient.getLock(key);
                lock.lock();
                try {
                    task.run();
                } finally {
                    lock.unlock();
                }
                return;
            } catch (Exception e) {
                log.warn("Redisson lock error for {}, falling back to local lock", lockName, e);
            }
        }
        // Local lock fallback
        ReentrantLock localLock = localLocks.computeIfAbsent(key, k -> new ReentrantLock());
        localLock.lock();
        try {
            task.run();
        } finally {
            localLock.unlock();
        }
    }
}
