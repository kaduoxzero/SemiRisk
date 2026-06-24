package com.semirisk.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 分布式锁配置（Phase 2）。
 *
 * <p>基于已有 Redis 集群，引入 Redisson 替代本地 JVM 锁 / AtomicBoolean，
 * 确保多实例部署时任务不重复执行。</p>
 *
 * <p>当 Redis 不可用时（本地开发无 Redis 实例），通过设置
 * {@code semirisk.redis.disabled=true} 来跳过 Redisson Bean 创建，
 * 分布式锁自动降级为本地 ReentrantLock。</p>
 */
@Configuration
@ConditionalOnProperty(name = "semirisk.redis.disabled", havingValue = "false", matchIfMissing = false)
public class RedissonConfig {

    private static final Logger log = LoggerFactory.getLogger(RedissonConfig.class);

    @Value("${semirisk.redis.cluster.nodes:127.0.0.1:6379}")
    private String redisNodes;

    @Value("${semirisk.redis.cluster.password:}")
    private String redisPassword;

    @Bean(destroyMethod = "")
    public RedissonClient redissonClient() {
        String[] nodes = redisNodes.split(",");
        Config config = new Config();
        String nodeAddr = "";

        // 尝试集群模式
        try {
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
            nodeAddr = clusterMode.toString();

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

            log.info("Redisson cluster client initialized with nodes: {}", nodeAddr);
            return Redisson.create(config);
        } catch (Exception e) {
            log.warn("Cluster mode failed, falling back to single-node. Error: {}", e.getMessage());
        }

        // 单机模式回退
        try {
            String singleNode = nodes[0].trim();
            if (!singleNode.startsWith("redis://")) {
                singleNode = "redis://" + singleNode;
            }
            nodeAddr = singleNode;

            SingleServerConfig singleConfig = config.useSingleServer()
                    .setAddress(singleNode)
                    .setConnectTimeout(5000)
                    .setTimeout(3000)
                    .setConnectionPoolSize(32)
                    .setConnectionMinimumIdleSize(8);

            if (redisPassword != null && !redisPassword.isBlank()) {
                singleConfig.setPassword(redisPassword);
            }

            log.info("Redisson single-node client initialized: {}", singleNode);
            return Redisson.create(config);
        } catch (Exception e) {
            log.error("Failed to create Redisson client at {}. Distributed locks will fall back to local locks.", nodeAddr, e);
            // 返回 null 让 DistributedLockManager 走降级路径
            return null;
        }
    }
}
