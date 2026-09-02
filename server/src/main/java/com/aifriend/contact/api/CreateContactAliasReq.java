package com.aifriend.contact.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 联系人方言称呼创建请求。
 *
 * @param displayText 辅助展示文字，不参与声学唯一性
 * @param phoneticHint 可选音素提示，不参与唯一性裁决
 * @param firstAudioObjectId 第一遍 ALIAS_ENROLLMENT 音频对象编号
 * @param secondAudioObjectId 第二遍 ALIAS_ENROLLMENT 音频对象编号
 * @param expectedContactVersion 当前联系人对外版本
 * @param confirmed 用户已确认保存称呼，只允许 true
 * @author Codex
 * @since 1.0.0
 */
public record CreateContactAliasReq(
        @NotBlank @Size(max = 40) String displayText,
        @Size(max = 100) String phoneticHint,
        @NotBlank @Pattern(regexp = "^au_[A-Za-z0-9]+$") String firstAudioObjectId,
        @NotBlank @Pattern(regexp = "^au_[A-Za-z0-9]+$") String secondAudioObjectId,
        @Min(1) long expectedContactVersion,
        @AssertTrue boolean confirmed) {
}
