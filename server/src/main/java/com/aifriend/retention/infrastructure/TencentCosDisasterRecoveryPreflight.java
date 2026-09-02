package com.aifriend.retention.infrastructure;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.BucketVersioningConfiguration;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.region.Region;

/**
 * 腾讯云 COS 三身份只读预检器。
 *
 * <p>预检只查询 Bucket 版本控制状态、使用发布身份枚举墓碑前缀，并使用导出和恢复
 * 身份对固定不存在对象执行 GET 权限探针。该类不提供任何写入或删除路径，也不是
 * Spring Bean。</p>
 *
 * @author codex
 * @since 1.0.0
 */
public final class TencentCosDisasterRecoveryPreflight implements AutoCloseable {

    /** 删除墓碑信封对象固定前缀。 */
    private static final String ENVELOPE_PREFIX = "deletion-tombstones/v1/";
    /** 只用于 GET 权限探测且不会创建的固定不存在对象键。 */
    private static final String READ_PROBE_OBJECT_KEY =
            ENVELOPE_PREFIX + "00000000-0000-0000-0000-000000000000.envelope";

    /** 三身份只读预检参数。 */
    private final TencentCosDisasterRecoveryPreflightOptions options;
    /** 快照发布身份 COS 客户端。 */
    private final COSClient publisherClient;
    /** 日常导出身份 COS 客户端。 */
    private final COSClient exportClient;
    /** 恢复身份 COS 客户端。 */
    private final COSClient restoreClient;

    /**
     * 使用三套已校验 CAM 参数创建只读预检器。
     *
     * @param options 三身份只读预检参数
     */
    public TencentCosDisasterRecoveryPreflight(
            TencentCosDisasterRecoveryPreflightOptions options) {
        this(
                options,
                createClient(
                        options,
                        options.publisherSecretId(),
                        options.publisherSecretKey(),
                        options.publisherSessionToken()),
                createClient(
                        options,
                        options.exportSecretId(),
                        options.exportSecretKey(),
                        options.exportSessionToken()),
                createClient(
                        options,
                        options.restoreSecretId(),
                        options.restoreSecretKey(),
                        options.restoreSessionToken()));
    }

    TencentCosDisasterRecoveryPreflight(
            TencentCosDisasterRecoveryPreflightOptions options,
            COSClient publisherClient,
            COSClient exportClient,
            COSClient restoreClient) {
        this.options = Objects.requireNonNull(options, "COS 灾备预检参数不能为空");
        this.publisherClient = Objects.requireNonNull(
                publisherClient, "COS 快照发布预检客户端不能为空");
        this.exportClient = Objects.requireNonNull(
                exportClient, "COS 日常导出预检客户端不能为空");
        this.restoreClient = Objects.requireNonNull(
                restoreClient, "COS 恢复预检客户端不能为空");
    }

    /**
     * 运行三身份只读预检。
     *
     * @throws IllegalStateException 当 Bucket 状态或任一身份权限不满足时抛出
     */
    public void verify() {
        verifyBucketVersioningOff(publisherClient, "快照发布");
        verifyBucketVersioningOff(exportClient, "日常导出");
        verifyBucketVersioningOff(restoreClient, "恢复");
        verifyPublisherListPermission();
        verifyReadPermission(exportClient, "日常导出");
        verifyReadPermission(restoreClient, "恢复");
    }

    /** 关闭三套 COS 客户端及连接池。 */
    @Override
    public void close() {
        publisherClient.shutdown();
        exportClient.shutdown();
        restoreClient.shutdown();
    }

    private void verifyBucketVersioningOff(COSClient client, String identityName) {
        try {
            BucketVersioningConfiguration versioning =
                    client.getBucketVersioningConfiguration(options.bucket());
            String status = versioning == null ? null : versioning.getStatus();
            if (status != null && !BucketVersioningConfiguration.OFF.equals(status)) {
                throw new IllegalStateException("COS 灾备 Bucket 必须保持版本控制从未开启");
            }
        } catch (CosClientException exception) {
            throw unavailable(identityName + "身份无法查询 Bucket 版本控制状态");
        }
    }

    private void verifyPublisherListPermission() {
        ListObjectsRequest request = new ListObjectsRequest();
        request.setBucketName(options.bucket());
        request.setPrefix(ENVELOPE_PREFIX);
        request.setMaxKeys(1);
        try {
            ObjectListing listing = publisherClient.listObjects(request);
            if (listing == null) {
                throw unavailable("快照发布身份无法枚举墓碑前缀");
            }
        } catch (CosClientException exception) {
            throw unavailable("快照发布身份无法枚举墓碑前缀");
        }
    }

    private void verifyReadPermission(COSClient client, String identityName) {
        try (COSObject storedObject =
                client.getObject(options.bucket(), READ_PROBE_OBJECT_KEY)) {
            if (storedObject == null) {
                throw unavailable(identityName + "身份无法验证墓碑对象读取权限");
            }
            throw unavailable(identityName + "身份读取探针对象时发现对象意外存在");
        } catch (CosServiceException exception) {
            if (exception.getStatusCode() == 404
                    || "NoSuchKey".equals(exception.getErrorCode())) {
                return;
            }
            throw unavailable(identityName + "身份缺少墓碑对象读取权限");
        } catch (IOException exception) {
            throw unavailable(identityName + "身份读取探针响应关闭失败");
        } catch (CosClientException exception) {
            throw unavailable(identityName + "身份无法验证墓碑对象读取权限");
        }
    }

    private static COSClient createClient(
            TencentCosDisasterRecoveryPreflightOptions options,
            String secretId,
            String secretKey,
            String sessionToken) {
        COSCredentials credentials = sessionToken.isBlank()
                ? new BasicCOSCredentials(secretId, secretKey)
                : new BasicSessionCredentials(secretId, secretKey, sessionToken);
        ClientConfig clientConfig = new ClientConfig(new Region(options.region()));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        clientConfig.setConnectionRequestTimeout(toMilliseconds(options.connectTimeout()));
        clientConfig.setConnectionTimeout(toMilliseconds(options.connectTimeout()));
        clientConfig.setSocketTimeout(toMilliseconds(options.readTimeout()));
        clientConfig.setRequestTimeout(toMilliseconds(options.readTimeout()));
        clientConfig.setRequestTimeOutEnable(true);
        clientConfig.setMaxConnectionsCount(4);
        clientConfig.setMaxErrorRetry(0);
        clientConfig.setCheckSSLCertificate(true);
        clientConfig.setRedirectsEnabled(false);
        return new COSClient(credentials, clientConfig);
    }

    private static int toMilliseconds(Duration duration) {
        return Math.toIntExact(duration.toMillis());
    }

    private static IllegalStateException unavailable(String stage) {
        return new IllegalStateException("COS 灾备三身份只读预检失败：" + stage);
    }
}
