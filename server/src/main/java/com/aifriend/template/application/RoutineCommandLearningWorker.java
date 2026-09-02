package com.aifriend.template.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 日常指令学习事务外音频、声学和加密编排工作器。
 *
 * <p>每次最多处理五个任务。明文音频、PCM、MFCC 和解密模板均只驻留当前
 * 调用内存并在结束时覆盖；日志和异常原因只允许稳定错误码。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class RoutineCommandLearningWorker {

    private static final int BATCH_SIZE = 5;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    private final RoutineCommandLearningTransactionService transactionService;
    private final RoutineCommandAudioEvidencePort audioEvidencePort;
    private final RoutineCommandAcousticTemplatePort acousticTemplatePort;
    private final SensitiveDataProtector protector;
    private final DigestService digestService;
    private final Clock clock;

    /**
     * 创建日常指令学习工作器。
     *
     * @param transactionService 学习短事务服务
     * @param audioEvidencePort 已消费 TASK 音频受限读取端口
     * @param acousticTemplatePort 本地单段 MFCC/DTW 端口
     * @param protector 模板 AES-GCM 保护器
     * @param digestService 模板完整性摘要服务
     * @param clock UTC 时钟
     */
    public RoutineCommandLearningWorker(
            RoutineCommandLearningTransactionService transactionService,
            RoutineCommandAudioEvidencePort audioEvidencePort,
            RoutineCommandAcousticTemplatePort acousticTemplatePort,
            SensitiveDataProtector protector,
            DigestService digestService,
            Clock clock) {
        this.transactionService = transactionService;
        this.audioEvidencePort = audioEvidencePort;
        this.acousticTemplatePort = acousticTemplatePort;
        this.protector = protector;
        this.digestService = digestService;
        this.clock = clock;
    }

    /** 领取并处理一批已到期学习任务。 */
    public void processReady() {
        List<RoutineCommandLearningJob> jobs = transactionService.claimReady(
                LEASE_DURATION, BATCH_SIZE);
        for (RoutineCommandLearningJob job : jobs) {
            processOne(job);
        }
    }

    private void processOne(RoutineCommandLearningJob job) {
        try {
            Optional<RoutineCommandTemplateSnapshot> snapshot =
                    transactionService.snapshot(job);
            if (snapshot.isEmpty()) {
                return;
            }
            processOutsideTransaction(job, snapshot.orElseThrow());
        } catch (BusinessException exception) {
            ErrorCode errorCode = exception.errorCode();
            boolean permanent = errorCode == ErrorCode.AUDIO_INVALID
                    || errorCode == ErrorCode.AUDIO_SEGMENT_UNCERTAIN;
            transactionService.settleFailure(
                    job, errorCode.name(), permanent);
        } catch (RuntimeException exception) {
            transactionService.settleFailure(
                    job, "LOCAL_PROCESSING_FAILED", false);
        }
    }

    private void processOutsideTransaction(
            RoutineCommandLearningJob job,
            RoutineCommandTemplateSnapshot snapshot) {
        List<RoutineCommandPlainTemplate> plainTemplates = new ArrayList<>();
        try (RoutineCommandAudioSnapshot audio = audioEvidencePort.read(job)) {
            loadCompatibleTemplates(job, snapshot.templates(), plainTemplates);
            try (RoutineCommandAcousticCandidate candidate =
                    acousticTemplatePort.extract(job, audio)) {
                Optional<RoutineCommandTemplateMatch> mergeTarget =
                        acousticTemplatePort.findMergeTarget(
                                candidate, plainTemplates, job);
                RoutineCommandTemplateWrite write = mergeTarget.isEmpty()
                        ? encryptCandidate(job, candidate) : null;
                transactionService.apply(
                        job, snapshot.namespaceVersion(),
                        mergeTarget.orElse(null), write);
            }
        } finally {
            plainTemplates.forEach(RoutineCommandPlainTemplate::close);
        }
    }

    private void loadCompatibleTemplates(
            RoutineCommandLearningJob job,
            List<RoutineCommandTemplateRecord> records,
            List<RoutineCommandPlainTemplate> result) {
        for (RoutineCommandTemplateRecord record : records) {
            if (!compatible(job, record)) {
                continue;
            }
            byte[] cipher = record.templateCipher();
            byte[] plain = null;
            byte[] actualDigest = null;
            try {
                plain = protector.decryptBytes(cipher);
                actualDigest = digestService.sha256(plain);
                if (!digestService.constantTimeEquals(
                        record.templateDigest(), actualDigest)) {
                    throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
                }
                result.add(new RoutineCommandPlainTemplate(
                        record.id(), record.version(), plain));
            } finally {
                Arrays.fill(cipher, (byte) 0);
                if (plain != null) {
                    Arrays.fill(plain, (byte) 0);
                }
                if (actualDigest != null) {
                    Arrays.fill(actualDigest, (byte) 0);
                }
            }
        }
    }

    private RoutineCommandTemplateWrite encryptCandidate(
            RoutineCommandLearningJob job,
            RoutineCommandAcousticCandidate candidate) {
        byte[] material = candidate.material();
        byte[] cipher = null;
        byte[] digest = null;
        try {
            cipher = protector.encryptBytes(material);
            digest = digestService.sha256(material);
            return new RoutineCommandTemplateWrite(
                    UUID.randomUUID(), job.intent(), job.dialectCode(),
                    job.dialectPackageVersion(), job.templateModelVersion(),
                    job.thresholdVersion(), cipher, digest, Instant.now(clock));
        } finally {
            Arrays.fill(material, (byte) 0);
            if (cipher != null) {
                Arrays.fill(cipher, (byte) 0);
            }
            if (digest != null) {
                Arrays.fill(digest, (byte) 0);
            }
        }
    }

    private boolean compatible(
            RoutineCommandLearningJob job,
            RoutineCommandTemplateRecord record) {
        return record.intent() == job.intent()
                && record.dialectCode().equals(job.dialectCode())
                && record.dialectPackageVersion().equals(
                        job.dialectPackageVersion())
                && record.templateModelVersion().equals(job.templateModelVersion())
                && record.thresholdVersion().equals(job.thresholdVersion());
    }
}
