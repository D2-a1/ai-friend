package com.aifriend.contact.application;

import java.util.List;

import com.aifriend.shared.error.BusinessException;

/**
 * 本地发音内容模板生成与唯一性比较端口。
 *
 * <p>实现不得执行说话人识别，不得使用展示文字替代声学唯一性，也不得在数据库事务中
 * 发起网络调用。未加载经授权语料校准的方言包、模型或阈值时必须失败关闭。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AcousticTemplatePort {

    /**
     * 校验两遍录音发音一致并生成候选模板。
     *
     * @param first 第一遍已校验录音
     * @param second 第二遍已校验录音
     * @return 带固定方言包、模型和阈值版本的模板候选
     * @throws BusinessException 两遍发音不一致或运行时未加载兼容引擎时抛出
     */
    AcousticEnrollmentCandidate enroll(
            AcousticEnrollmentSample first,
            AcousticEnrollmentSample second);

    /**
     * 在 owner 锁内将候选与最多 100 个有效模板进行发音内容比较。
     *
     * <p>签名方言包使用校准绝对三档。基础体验另外允许双方双录均满足注册
     * 一致性距离、且最小跨称呼距离相对双录基线满足运行时余量的稳定分离证据；
     * 绝对冲突仍优先拒绝，任一已有模板无法区分则不能注册。
     *
     * @param candidate 待注册模板
     * @param existingTemplates owner 的全部有效模板
     * @return 唯一、冲突或边界分类
     * @throws BusinessException 模型或阈值版本不兼容时抛出
     */
    AcousticUniqueness classify(
            AcousticEnrollmentCandidate candidate,
            List<ExistingAcousticTemplate> existingTemplates);

    /**
     * 判断安全指令候选是否相对自身双录基线与其他指令可靠分离。
     *
     * <p>安全指令由同一人连续录制，不能只用跨类别绝对距离判定，否则说话人共同特征
     * 会让不同短句长期落入同一临界区。本方法要求跨类别距离相对双方各自双录距离
     * 留有当前方言包规定的最小间隔；不使用文字、ASR 或说话人身份。
     *
     * @param candidate 当前安全指令候选
     * @param existingTemplates 已通过的其他安全指令模板
     * @return 当前候选与全部其他指令均有可靠相对间隔时返回 true
     * @throws BusinessException 模型或阈值版本不兼容时抛出
     */
    boolean isSafetyCommandDistinct(
            AcousticEnrollmentCandidate candidate,
            List<ExistingAcousticTemplate> existingTemplates);

    /**
     * 判断已持久化模板版本是否与当前已验证方言包兼容。
     *
     * <p>该查询只能读取本地已验证注册表，不得读取模板明文或发起网络调用。
     * 方言包缺失、关闭或版本不同均返回 false，不抛出以保证清单接口仍可用。
     *
     * @param dialectCode 方言代码
     * @param dialectPackageVersion 方言包版本
     * @param modelVersion 模板模型版本
     * @param thresholdVersion 阈值版本
     * @return 四项版本均与当前已验证包一致时返回 true
     */
    boolean isCompatible(
            String dialectCode,
            String dialectPackageVersion,
            String modelVersion,
            String thresholdVersion);
}
