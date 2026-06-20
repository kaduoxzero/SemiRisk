package com.semirisk.config;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁工具（Phase 2）。
 * 基于 Redisson RLock，替代原有的 AtomicBoolean / synchronized。
 */
@Component
public class DistributedLockManager {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockManager.class);
    private static final String LOCK_PREFIX = "semirisk:lock:";

    private final RedissonClient redissonClient;

    public DistributedLockManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试获取分布式锁并执行任务。
     *
     * @param lockName    锁名称（唯一标识）
     * @param waitMs      等待锁的最大毫秒数
     * @param leaseMs     锁持有时间（自动释放），0 表示不自动释放
     * @param task        获取锁后执行的任务
     * @return 任务返回值，获取锁失败则返回 null
     */
    public <T> T executeWithLock(String lockName, long waitMs, long leaseMs, Supplier<T> task) {
        String key = LOCK_PREFIX + lockName;
        RLock lock = redissonClient.getLock(key);
        try {
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
            log.error("Error executing task under lock: {}", lockName, e);
            throw e;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 简化版：不带超时，执行任务。
     */
    public void executeWithLockSimple(String lockName, Runnable task) {
        String key = LOCK_PREFIX + lockName;
        RLock lock = redissonClient.getLock(key);
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }
}
