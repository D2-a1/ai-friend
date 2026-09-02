package com.aifriend.voice.application;

/**
 * 临时音频对象存储端口。
 *
 * <p>原始音频只通过本端口读写私有对象存储，不得进入 MySQL 或业务日志。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AudioObjectStoragePort {

    /**
     * 以只创建不覆盖语义保存音频对象。
     *
     * @param objectKey 服务端生成的私有对象键
     * @param audioContent 原始音频字节，不得记录
     * @return 对象存储版本标识
     */
    String store(String objectKey, byte[] audioContent);

    /**
     * 有界读取当前私有对象并返回可用于后续精确复验的强版本。
     *
     * <p>仅用于没有独立“上传完成”接口的生产直传发现。实现必须返回非空、
     * 非空白且随对象内容变化的版本标识，不能把弱时间戳伪装成对象版本。
     *
     * @param objectKey 服务端生成的私有对象键
     * @param maximumBytes 本次允许读入内存的最大字节数
     * @return 当前对象及其强版本
     */
    StoredAudioObject readCurrent(String objectKey, long maximumBytes);

    /**
     * 按指定存储版本读取音频对象。
     *
     * <p>实现必须使用条件读或读后版本复验，不能将被覆盖的新内容伪装成已验证版本。
     *
     * @param objectKey 服务端生成的私有对象键
     * @param expectedStorageVersion 上传完成时记录的对象存储版本
     * @param maximumBytes 本次允许读入内存的最大字节数
     * @return 已复验版本的存储对象，原始音频仅留在当前调用内存
     */
    StoredAudioObject readExact(
            String objectKey,
            String expectedStorageVersion,
            long maximumBytes);

    /**
     * 幂等删除临时音频对象。
     *
     * <p>实现只有在确认对象已不存在后才能成功返回。对象原本不存在按幂等成功处理；
     * 删除请求或不存在复验失败时必须抛出异常，由生命周期工作器记录退避并重试。
     *
     * @param objectKey 服务端生成的私有对象键
     * @throws RuntimeException 当删除请求或对象不存在复验失败时抛出
     */
    void delete(String objectKey);
}
