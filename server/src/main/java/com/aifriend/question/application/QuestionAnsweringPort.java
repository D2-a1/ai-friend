package com.aifriend.question.application;

/**
 * 生活问答端口。
 *
 * <p>当前仅占位，不注册实现、不开放 API，不调用云模型或外部搜索。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface QuestionAnsweringPort {

    /**
     * 生成带来源的问答草稿。
     *
     * @param question 去除敏感信息后的问题
     * @return 问答草稿
     */
    String prepareAnswer(String question);
}
