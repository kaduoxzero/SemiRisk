package com.semirisk.common;

public record MiddlewareDefaults(
        String host,
        int mysqlPort,
        int redisPort,
        int elasticsearchPort,
        int minioPort,
        int rabbitmqPort,
        int nacosPort
) {
    public static MiddlewareDefaults vmDefaults() {
        return new MiddlewareDefaults(SemiriskConstants.DEFAULT_MIDDLEWARE_HOST, 3306, 6379, 9200, 9000, 5672, 8848);
    }
}
