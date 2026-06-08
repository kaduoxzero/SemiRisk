package com.semirisk.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinIO 对象存储服务。
 *
 * <p>上传的供应链数据文件与知识库文档真实落入 MinIO（VM {@code :9000}），
 * 预览与下载从 MinIO 拉取真实对象流，替代此前的模拟文本。MinIO 暂不可达时方法抛出异常，
 * 由调用方给出明确的降级状态，不伪造文件内容。</p>
 */
@Service
public class MinioStorageService {

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;
    private volatile MinioClient client;
    private volatile boolean bucketReady = false;

    public MinioStorageService(
            @Value("${semirisk.minio.endpoint:http://${semirisk.middleware.host:192.168.101.130}:9000}") String endpoint,
            @Value("${semirisk.minio.access-key:minioadmin}") String accessKey,
            @Value("${semirisk.minio.secret-key:minioadmin}") String secretKey,
            @Value("${semirisk.minio.bucket:semirisk}") String bucket) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.bucket = bucket;
    }

    public String bucket() {
        return bucket;
    }

    public boolean available() {
        try {
            client().bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 上传对象，返回 objectKey。失败抛出异常由调用方处理。 */
    public String putObject(String objectKey, byte[] content, String contentType) throws Exception {
        ensureBucket();
        try (InputStream stream = new ByteArrayInputStream(content)) {
            client().putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(stream, content.length, -1)
                    .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                    .build());
        }
        return objectKey;
    }

    /** 读取对象字节流。 */
    public byte[] getObject(String objectKey) throws Exception {
        try (InputStream stream = client().getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return stream.readAllBytes();
        }
    }

    public String contentType(String objectKey) {
        try {
            StatObjectResponse stat = client().statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return stat.contentType();
        } catch (Exception ex) {
            return "application/octet-stream";
        }
    }

    public boolean objectExists(String objectKey) {
        try {
            client().statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private synchronized void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        boolean exists = client().bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client().makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        bucketReady = true;
    }

    private MinioClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = MinioClient.builder()
                            .endpoint(endpoint)
                            .credentials(accessKey, secretKey)
                            .build();
                }
            }
        }
        return client;
    }
}
