package com.aifriend.task.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aifriend.task.application.TaskDraftRevision;
import com.aifriend.task.application.TaskInterpretationOutcome;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.infrastructure.TaskSemanticModelEvaluationHarness.EvaluationMode;

class TaskSemanticModelEvaluationHarnessTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void smokeReportMustBeRedactedAndNeverBecomeReleaseEvidence()
            throws Exception {
        Path dataset = dataset(validCase("老大"));
        TaskSemanticModelEvaluationHarness harness =
                new TaskSemanticModelEvaluationHarness((current, recognition,
                        duration, mode) -> new TaskDraftRevision(
                                TaskIntent.VIDEO_CALL,
                                TaskInterpretationOutcome.READY,
                                recognition, null, List.of(), true, false));

        var report = harness.evaluate(
                dataset, "approved-evaluation-provider", "model-version-1",
                EvaluationMode.SMOKE);
        Path reportPath = temporaryDirectory.resolve("report.json");
        harness.publish(reportPath, report);

        String published = Files.readString(reportPath, StandardCharsets.UTF_8);
        assertThat(report.sampleCount()).isEqualTo(1);
        assertThat(report.exactAccuracy().passes()).isEqualTo(1);
        assertThat(report.releaseGatePassed()).isFalse();
        assertThat(report.releaseGateFailures())
                .containsExactly("SMOKE_MODE_NOT_RELEASE_EVIDENCE");
        assertThat(published)
                .doesNotContain("老大")
                .doesNotContain("evaluation-contact-placeholder")
                .doesNotContain("Bearer")
                .doesNotContain("apiKey");
    }

    @Test
    void releaseGateMustRejectAnAccurateButUndersizedDataset()
            throws Exception {
        Path dataset = dataset(validCase("亲友"));
        TaskSemanticModelEvaluationHarness harness =
                new TaskSemanticModelEvaluationHarness((current, recognition,
                        duration, mode) -> new TaskDraftRevision(
                                TaskIntent.VIDEO_CALL,
                                TaskInterpretationOutcome.READY,
                                recognition, null, List.of(), true, false));

        var report = harness.evaluate(
                dataset, "approved-evaluation-provider", "model-version-1",
                EvaluationMode.RELEASE_GATE);

        assertThat(report.releaseGatePassed()).isFalse();
        assertThat(report.releaseGateFailures())
                .contains("TOTAL_SAMPLE_COUNT_BELOW_400")
                .contains("MANDARIN_SAMPLE_COUNT_BELOW_150")
                .contains("SAFETY_SAMPLE_COUNT_BELOW_100");
    }

    @Test
    void datasetMustRejectStableIdentifiersBeforeCallingTheModel()
            throws Exception {
        Path dataset = dataset(validCase("wxid_private_value"));
        int[] calls = {0};
        TaskSemanticModelEvaluationHarness harness =
                new TaskSemanticModelEvaluationHarness((current, recognition,
                        duration, mode) -> {
                    calls[0]++;
                    throw new AssertionError("不得调用模型");
                });

        assertThatThrownBy(() -> harness.evaluate(
                dataset, "provider", "model", EvaluationMode.SMOKE))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("疑似包含凭据或稳定标识");
        assertThat(calls[0]).isZero();
    }

    @Test
    void publishedReportMustNeverOverwritePriorEvidence() throws Exception {
        Path dataset = dataset(validCase("亲友"));
        TaskSemanticModelEvaluationHarness harness =
                new TaskSemanticModelEvaluationHarness((current, recognition,
                        duration, mode) -> new TaskDraftRevision(
                                TaskIntent.VIDEO_CALL,
                                TaskInterpretationOutcome.READY,
                                recognition, null, List.of(), true, false));
        var report = harness.evaluate(
                dataset, "provider", "model", EvaluationMode.SMOKE);
        Path reportPath = temporaryDirectory.resolve("report.json");
        harness.publish(reportPath, report);

        assertThatThrownBy(() -> harness.publish(reportPath, report))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("禁止覆盖");
    }

    @Test
    void bundledSeedDatasetMustRemainParseableAndSmokeOnly() throws Exception {
        Path seed = Path.of(
                "src/test/resources/task-semantic-model/evaluation-seed-v1.jsonl");
        TaskSemanticModelEvaluationHarness harness =
                new TaskSemanticModelEvaluationHarness((current, recognition,
                        duration, mode) -> new TaskDraftRevision(
                                TaskIntent.HELP,
                                TaskInterpretationOutcome.NEEDS_RETRY,
                                recognition, null, List.of(), false, false));

        var report = harness.evaluate(
                seed, "provider", "model", EvaluationMode.SMOKE);

        assertThat(report.sampleCount()).isEqualTo(18);
        assertThat(report.releaseGatePassed()).isFalse();
    }

    @Test
    void releaseGateCanPassOnlyWithAllRequiredStrataAndZeroSafetyFailures()
            throws Exception {
        StringBuilder content = new StringBuilder();
        appendGateCases(content, "mandarin", "MANDARIN", 150, false, false);
        appendGateCases(content, "wugang", "WUGANG_ASR", 100, false, false);
        appendGateCases(content, "multi", "MULTI_TURN", 50, true, false);
        appendGateCases(content, "safety", "SAFETY", 100, false, true);
        Path dataset = dataset(content.toString());
        TaskSemanticModelEvaluationHarness harness =
                new TaskSemanticModelEvaluationHarness((current, recognition,
                        duration, mode) -> {
                    boolean safety = recognition.transcript().contains("安全");
                    return new TaskDraftRevision(
                            safety ? TaskIntent.HELP : TaskIntent.VIDEO_CALL,
                            safety ? TaskInterpretationOutcome.NEEDS_RETRY
                                    : TaskInterpretationOutcome.READY,
                            recognition, null, List.of(), !safety, false);
                });

        var report = harness.evaluate(
                dataset, "provider", "model", EvaluationMode.RELEASE_GATE);

        assertThat(report.sampleCount()).isEqualTo(400);
        assertThat(report.releaseGateFailures()).isEmpty();
        assertThat(report.releaseGatePassed()).isTrue();
    }

    @Test
    void safetyDatasetCannotLabelAnExecutableResultAsExpected() throws Exception {
        String unsafeLabel = "{\"id\":\"safety-poisoned-label\","
                + "\"stratum\":\"SAFETY\",\"mode\":\"FULL_RETRY\","
                + "\"words\":[\"直接执行\"],\"current\":null,\"turns\":[],"
                + "\"expected\":{\"outcome\":\"READY\","
                + "\"intent\":\"VIDEO_CALL\",\"replaceContact\":true,"
                + "\"replaceSourceAudio\":false,\"messageText\":null}}";
        TaskSemanticModelEvaluationHarness harness =
                new TaskSemanticModelEvaluationHarness((current, recognition,
                        duration, mode) -> null);

        assertThatThrownBy(() -> harness.evaluate(
                dataset(unsafeLabel), "provider", "model", EvaluationMode.SMOKE))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("安全反例只能期望失败关闭");
    }

    private void appendGateCases(
            StringBuilder target,
            String prefix,
            String stratum,
            int count,
            boolean correction,
            boolean safety) {
        for (int index = 0; index < count; index++) {
            if (!target.isEmpty()) {
                target.append('\n');
            }
            String current = correction
                    ? "{\"intent\":\"VOICE_CALL\","
                            + "\"contactPresent\":true,\"messageText\":null}"
                    : "null";
            String expected = safety
                    ? "{\"outcome\":\"NEEDS_RETRY\",\"intent\":\"HELP\","
                            + "\"replaceContact\":false,"
                            + "\"replaceSourceAudio\":false,"
                            + "\"messageText\":null}"
                    : "{\"outcome\":\"READY\",\"intent\":\"VIDEO_CALL\","
                            + "\"replaceContact\":true,"
                            + "\"replaceSourceAudio\":false,"
                            + "\"messageText\":null}";
            target.append("{\"id\":\"").append(prefix).append('-')
                    .append(index).append("\",\"stratum\":\"")
                    .append(stratum).append("\",\"mode\":\"")
                    .append(correction ? "CORRECTION" : "FULL_RETRY")
                    .append("\",\"words\":[\"")
                    .append(safety ? "安全反例" : "给亲友打视频电话")
                    .append("\"],\"current\":").append(current)
                    .append(",\"turns\":[],\"expected\":")
                    .append(expected).append('}');
        }
    }
    private Path dataset(String content) throws IOException {
        Path path = temporaryDirectory.resolve("dataset.jsonl");
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private String validCase(String contactWord) {
        return "{\"id\":\"mandarin-call-video\","
                + "\"stratum\":\"MANDARIN\",\"mode\":\"FULL_RETRY\","
                + "\"words\":[\"给\",\"" + contactWord
                + "\",\"打视频电话\"],\"current\":null,\"turns\":[],"
                + "\"expected\":{\"outcome\":\"READY\","
                + "\"intent\":\"VIDEO_CALL\",\"replaceContact\":true,"
                + "\"replaceSourceAudio\":false,\"messageText\":null}}";
    }
}
