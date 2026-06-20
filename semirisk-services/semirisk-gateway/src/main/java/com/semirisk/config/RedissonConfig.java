package com.semirisk.config;

import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 分布式锁配置（Phase 2）。
 *
 * <p>基于已有 Redis 集群，引入 Redisson 替代本地 JVM 锁 / AtomicBoolean，
 * 确保多实例部署时任务不重复执行。</p>
 */
@Configuration
public class RedissonConfig {

    private static final Logger log = LoggerFactory.getLogger(RedissonConfig.class);

    @Value("${semirisk.redis.cluster.nodes}")
    private String redisNodes;

    @Value("${semirisk.redis.cluster.max-redirects:3}")
    private int maxRedirects;

    @Value("${semirisk.redis.cluster.password:}")
    private String redisPassword;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 解析集群节点
        String[] nodes = redisNodes.split(",");
        StringBuilder clusterMode = new StringBuilder();
        for (int i = 0; i < nodes.length; i++) {
            String node = nodes[i].trim();
            if (!node.startsWith("redis://")) {
                node = "redis://" + node;
            }
            clusterMode.append(node);
            if (i < nodes.length - 1) {
                clusterMode.append(",");
            }
        }

        ClusterServersConfig clusterConfig = config.useClusterServers()
                .addNodeAddress(clusterMode.toString().split(","))
                .setConnectTimeout(5000)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setRetryInterval(1500)
                .setMasterConnectionPoolSize(32)
                .setMasterConnectionMinimumIdleSize(8)
                .setSlaveConnectionPoolSize(64)
                .setSlaveConnectionMinimumIdleSize(16);

        if (redisPassword != null && !redisPassword.isBlank()) {
            clusterConfig.setPassword(redisPassword);
        }

        log.info("Redisson cluster client initialized with nodes: {}", clusterMode);
        return Redisson.create(config);
    }
}
