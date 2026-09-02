package com.aifriend.retention.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.aifriend.shared.security.DigestService;

/**
 * 删除墓碑离线快照发布命令行入口。
 *
 * <p>全部凭据与 Ed25519 材料只从当前进程环境读取，不接受命令行秘密参数。该入口不
 * 启动 Spring，也不连接 MySQL 或 Redis。</p>
 *
 * @author codex
 * @since 1.0.0
 */
public final class DisasterRecoverySnapshotPublisherCli {

    private DisasterRecoverySnapshotPublisherCli() {
    }

    /**
     * 从当前进程环境构建并发布快照。
     *
     * @param arguments 不接受任何命令行参数
     */
    public static void main(String[] arguments) {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("离线快照发布工具不接受命令行参数");
        }
        Map<String, String> environment = System.getenv();
        if (preflightOnly(environment)) {
            runPreflight(preflightOptions(environment));
            return;
        }
        TencentCosSnapshotPublisherOptions options = options(environment);
        try (TencentCosDeletionTombstoneSnapshotPublisher publisher =
                new TencentCosDeletionTombstoneSnapshotPublisher(
                        options, new DigestService(), new ObjectMapper())) {
            TencentCosDeletionTombstoneSnapshotPublisher.SnapshotPublicationReceipt receipt =
                    publisher.publish();
            System.out.println("删除墓碑快照发布成功");
            System.out.println("快照编号：" + receipt.snapshotId());
            System.out.println("生成时间：" + receipt.createdAt());
            System.out.println("墓碑数量：" + receipt.itemCount());
            System.out.println("索引页数：" + receipt.pageCount());
            System.out.println("根清单摘要："
                    + HexFormat.of().withUpperCase().formatHex(receipt.manifestHash()));
        } catch (RuntimeException exception) {
            System.err.println("删除墓碑快照发布失败；未生成可信根清单");
            throw exception;
        }
    }

    private static void runPreflight(
            TencentCosDisasterRecoveryPreflightOptions options) {
        try (TencentCosDisasterRecoveryPreflight preflight =
                new TencentCosDisasterRecoveryPreflight(options)) {
            preflight.verify();
            System.out.println("COS 灾备三身份只读预检通过");
            System.out.println("未创建、修改或删除任何 COS 对象");
        } catch (RuntimeException exception) {
            System.err.println("COS 灾备三身份只读预检失败；未执行快照发布");
            throw exception;
        }
    }

    static TencentCosDisasterRecoveryPreflightOptions preflightOptions(
            Map<String, String> environment) {
        Objects.requireNonNull(environment, "进程环境不能为空");
        return new TencentCosDisasterRecoveryPreflightOptions(
                required(environment, "AI_FRIEND_DR_PREFLIGHT_REGION"),
                required(environment, "AI_FRIEND_DR_PREFLIGHT_BUCKET"),
                required(environment, "AI_FRIEND_DR_PREFLIGHT_PUBLISH_SECRET_ID"),
                required(environment, "AI_FRIEND_DR_PREFLIGHT_PUBLISH_SECRET_KEY"),
                optional(environment, "AI_FRIEND_DR_PREFLIGHT_PUBLISH_SESSION_TOKEN"),
                required(environment, "AI_FRIEND_DR_PREFLIGHT_EXPORT_SECRET_ID"),
                required(environment, "AI_FRIEND_DR_PREFLIGHT_EXPORT_SECRET_KEY"),
                optional(environment, "AI_FRIEND_DR_PREFLIGHT_EXPORT_SESSION_TOKEN"),
                required(environment, "AI_FRIEND_DR_PREFLIGHT_RESTORE_SECRET_ID"),
                required(environment, "AI_FRIEND_DR_PREFLIGHT_RESTORE_SECRET_KEY"),
                optional(environment, "AI_FRIEND_DR_PREFLIGHT_RESTORE_SESSION_TOKEN"),
                duration(environment, "AI_FRIEND_DR_PREFLIGHT_CONNECT_TIMEOUT", "PT2S"),
                duration(environment, "AI_FRIEND_DR_PREFLIGHT_READ_TIMEOUT", "PT5S"));
    }

    static boolean preflightOnly(Map<String, String> environment) {
        String configured = optional(environment, "AI_FRIEND_DR_PREFLIGHT_ONLY");
        if (configured.isBlank()) {
            return false;
        }
        if ("true".equalsIgnoreCase(configured)) {
            return true;
        }
        if ("false".equalsIgnoreCase(configured)) {
            return false;
        }
        throw new IllegalArgumentException("COS 灾备预检开关必须为 true 或 false");
    }

    static TencentCosSnapshotPublisherOptions options(Map<String, String> environment) {
        Objects.requireNonNull(environment, "进程环境不能为空");
        return new TencentCosSnapshotPublisherOptions(
                required(environment, "AI_FRIEND_DR_PUBLISH_REGION"),
                required(environment, "AI_FRIEND_DR_PUBLISH_BUCKET"),
                required(environment, "AI_FRIEND_DR_PUBLISH_SECRET_ID"),
                required(environment, "AI_FRIEND_DR_PUBLISH_SECRET_KEY"),
                optional(environment, "AI_FRIEND_DR_PUBLISH_SESSION_TOKEN"),
                required(environment, "AI_FRIEND_DR_EXPORT_SECRET_ID"),
                required(environment, "AI_FRIEND_DR_RESTORE_SECRET_ID"),
                required(environment, "AI_FRIEND_DR_PUBLISH_SNAPSHOT_ID"),
                instant(environment, "AI_FRIEND_DR_PUBLISH_CREATED_AT"),
                integer(environment, "AI_FRIEND_DR_PUBLISH_PAGE_SIZE", "100"),
                required(environment, "AI_FRIEND_DR_MANIFEST_PRIVATE_KEY_PKCS8_BASE64"),
                required(environment, "AI_FRIEND_DR_MANIFEST_PUBLIC_KEY_X509_BASE64"),
                duration(environment, "AI_FRIEND_DR_PUBLISH_CONNECT_TIMEOUT", "PT2S"),
                duration(environment, "AI_FRIEND_DR_PUBLISH_READ_TIMEOUT", "PT5S"));
    }

    private static String required(Map<String, String> environment, String name) {
        String value = optional(environment, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少离线快照发布环境变量：" + name);
        }
        return value;
    }

    private static String optional(Map<String, String> environment, String name) {
        return Objects.requireNonNullElse(environment.get(name), "").trim();
    }

    private static int integer(
            Map<String, String> environment,
            String name,
            String defaultValue) {
        try {
            return Integer.parseInt(Objects.requireNonNullElse(
                    environment.get(name), defaultValue).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("离线快照发布整数配置无效：" + name);
        }
    }

    private static Instant instant(Map<String, String> environment, String name) {
        try {
            return Instant.parse(required(environment, name));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("离线快照生成时间必须使用 ISO-8601 UTC");
        }
    }

    private static Duration duration(
            Map<String, String> environment,
            String name,
            String defaultValue) {
        try {
            return Duration.parse(Objects.requireNonNullElse(
                    environment.get(name), defaultValue).trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("离线快照超时配置无效：" + name);
        }
    }
}
