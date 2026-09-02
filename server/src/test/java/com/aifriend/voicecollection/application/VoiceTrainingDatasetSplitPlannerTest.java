package com.aifriend.voicecollection.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;

class VoiceTrainingDatasetSplitPlannerTest {

    private final VoiceTrainingDatasetSplitPlanner planner =
            new VoiceTrainingDatasetSplitPlanner();

    @Test
    void shouldKeepDuplicateAudioTogetherAndProduceBothSplits() {
        byte[] firstDigest = digest(1);
        List<VoiceTrainingInputExportMember> members = List.of(
                member(0, firstDigest),
                member(1, firstDigest),
                member(2, digest(2)),
                member(3, digest(3)));

        List<VoiceTrainingInputSplit> first = planner.plan(members);
        List<VoiceTrainingInputSplit> repeated = planner.plan(members);

        assertEquals(first, repeated);
        assertEquals(first.get(0), first.get(1));
        assertTrue(first.contains(VoiceTrainingInputSplit.TRAIN));
        assertTrue(first.contains(VoiceTrainingInputSplit.VALIDATION));
    }

    @Test
    void shouldKeepDigestAssignmentWhenMemberOrderChanges() {
        VoiceTrainingInputExportMember first = member(0, digest(1));
        VoiceTrainingInputExportMember second = member(1, digest(2));
        VoiceTrainingInputExportMember third = member(2, digest(3));
        List<VoiceTrainingInputExportMember> original = List.of(first, second, third);
        List<VoiceTrainingInputExportMember> reordered = List.of(third, first, second);

        assertEquals(
                splitByDigest(original, planner.plan(original)),
                splitByDigest(reordered, planner.plan(reordered)));
    }

    @Test
    void shouldRejectDatasetWithOnlyOneDistinctAudio() {
        byte[] sameDigest = digest(7);
        List<VoiceTrainingInputExportMember> members = List.of(
                member(0, sameDigest),
                member(1, sameDigest));

        assertThrows(BusinessException.class, () -> planner.plan(members));
    }

    private Map<String, VoiceTrainingInputSplit> splitByDigest(
            List<VoiceTrainingInputExportMember> members,
            List<VoiceTrainingInputSplit> splits) {
        Map<String, VoiceTrainingInputSplit> result = new LinkedHashMap<>();
        for (int index = 0; index < members.size(); index++) {
            result.put(
                    HexFormat.of().formatHex(members.get(index).audioSha256()),
                    splits.get(index));
        }
        return result;
    }

    private VoiceTrainingInputExportMember member(int order, byte[] audioDigest) {
        return new VoiceTrainingInputExportMember(
                UUID.nameUUIDFromBytes(("sample-" + order).getBytes(StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes(("audio-" + order).getBytes(StandardCharsets.UTF_8)),
                order,
                1L,
                1L,
                audioDigest,
                new byte[32],
                new byte[32],
                VoiceCollectionCategory.FULL_TASK,
                "full_task_contact_message",
                VoiceCollectionEnvironment.QUIET,
                "zh-Hans-CN-x-wugang");
    }

    private byte[] digest(int marker) {
        byte[] digest = new byte[32];
        Arrays.fill(digest, (byte) marker);
        return digest;
    }
}
