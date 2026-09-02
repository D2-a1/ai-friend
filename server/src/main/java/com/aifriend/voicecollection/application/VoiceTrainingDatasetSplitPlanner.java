package com.aifriend.voicecollection.application;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 根据音频摘要生成可复现且无重复音频泄漏的训练/验证划分。
 *
 * <p>相同音频摘要始终作为一个整体分组。正常情况下约五分之一摘要组进入验证集；
 * 若稳定分桶导致某一侧为空，则按摘要字典序做确定性修正。</p>
 *
 * @author codex
 * @since 1.0.0
 */
@Component
public class VoiceTrainingDatasetSplitPlanner {

    private static final int VALIDATION_BUCKET_MODULUS = 5;

    /**
     * 创建训练/验证划分器。
     */
    public VoiceTrainingDatasetSplitPlanner() {
    }

    /**
     * 为冻结顺序的训练输入成员生成稳定划分。
     *
     * @param members 已完成实时门禁核验的冻结成员
     * @return 与成员顺序一一对应的不可变划分
     * @throws BusinessException 当成员无效或不足两个不同音频时抛出
     */
    public List<VoiceTrainingInputSplit> plan(
            List<VoiceTrainingInputExportMember> members) {
        if (members == null || members.size() < 2) {
            throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        }
        Set<String> digestGroups = new TreeSet<>();
        for (VoiceTrainingInputExportMember member : members) {
            byte[] digest = member == null ? null : member.audioSha256();
            if (digest == null || digest.length != 32) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            String digestHex = HexFormat.of().formatHex(digest);
            digestGroups.add(digestHex);
        }
        if (digestGroups.size() < 2) {
            throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        }
        List<String> orderedDigests = List.copyOf(digestGroups);
        Set<String> validationDigests = new TreeSet<>();
        for (String digest : orderedDigests) {
            if (validationBucket(digest) == 0) {
                validationDigests.add(digest);
            }
        }
        if (validationDigests.isEmpty()) {
            validationDigests.add(orderedDigests.get(0));
        } else if (validationDigests.size() == orderedDigests.size()) {
            validationDigests.remove(orderedDigests.get(orderedDigests.size() - 1));
        }
        List<VoiceTrainingInputSplit> splits = new ArrayList<>(members.size());
        for (VoiceTrainingInputExportMember member : members) {
            String digest = HexFormat.of().formatHex(member.audioSha256());
            splits.add(validationDigests.contains(digest)
                    ? VoiceTrainingInputSplit.VALIDATION
                    : VoiceTrainingInputSplit.TRAIN);
        }
        return List.copyOf(splits);
    }

    private int validationBucket(String digestHex) {
        long prefix = Long.parseUnsignedLong(digestHex.substring(0, 8), 16);
        return (int) (prefix % VALIDATION_BUCKET_MODULUS);
    }
}
