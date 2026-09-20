package com.aifriend.retrieval.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.KnowledgeDocument;
import com.aifriend.retrieval.domain.KnowledgeText;

/**
 * 确定性原文分块，优先段落/标题边界，其次句子，最后有界硬切。
 * @author codex
 * @since 1.0.0
 */
public final class KnowledgeChunker implements com.aifriend.retrieval.application.KnowledgeChunkingPort {
    private final int target;
    private final int overlap;
    private final int maximum;
    private final String version;

    /**
     * 创建版本化分块器，所有配置参与算法版本。
     * @param target 目标码点数，默认配置应为400
     * @param overlap 重叠码点数，默认配置应为60
     * @param maximum 硬上限，最大600
     */
    public KnowledgeChunker(int target, int overlap, int maximum) {
        if (target < 1 || maximum < target || maximum > 600 || overlap < 0 || overlap >= target) {
            throw new IllegalArgumentException("INVALID_CHUNK_CONFIGURATION");
        }
        this.target = target;
        this.overlap = overlap;
        this.maximum = maximum;
        this.version = "cp-paragraph-v1-t" + target + "-o" + overlap + "-m" + maximum;
    }

    /** @return 决定重建兼容性的分块版本 */
    public String version() { return version; }

    /**
     * 严格UTF-8解码，不以替代字符接受损坏输入。
     * @param bytes JSON之外已有的受限文本字节
     * @return 未规范化原文
     */
    public static String decodeUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 262144) {
            throw new IllegalArgumentException("INPUT_LIMIT");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return KnowledgeText.require(text, 262144, 262144, false);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("INVALID_TEXT", exception);
        }
    }

    /**
     * 返回覆盖完整原文的稳定片段，任何越限拒绝整文。
     * @param document 已校验公开文档版本
     * @return 不可变片段列表
     */
    public List<KnowledgeChunk> chunk(KnowledgeDocument document) {
        Objects.requireNonNull(document, "document");
        int[] points = document.text().codePoints().toArray();
        var result = new ArrayList<KnowledgeChunk>();
        int start = 0;
        while (start < points.length) {
            if (result.size() >= 2000) {
                throw new IllegalArgumentException("CHUNK_LIMIT");
            }
            int end = chooseEnd(points, start);
            String key = document.id() + ":" + document.version() + ":" + version + ":" + start + ":" + end;
            UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
            result.add(new KnowledgeChunk(id, document.id(), document.version(), result.size(),
                    heading(points, start), new String(points, start, end - start), start, end, version));
            if (end == points.length) {
                break;
            }
            start = end - overlap;
        }
        return List.copyOf(result);
    }

    private int chooseEnd(int[] points, int start) {
        if (points.length - start <= target) {
            return points.length;
        }
        int preferred = start + target;
        int minimum = start + overlap + 1;
        int hardEnd = Math.min(points.length, start + maximum);
        for (int end = preferred; end >= minimum; end--) {
            if (paragraphBoundary(points, end)) {
                return end;
            }
        }
        for (int end = preferred + 1; end <= hardEnd; end++) {
            if (paragraphBoundary(points, end)) {
                return end;
            }
        }
        for (int end = preferred; end >= minimum; end--) {
            if (sentenceBoundary(points[end - 1])) {
                return end;
            }
        }
        for (int end = preferred + 1; end <= hardEnd; end++) {
            if (sentenceBoundary(points[end - 1])) {
                return end;
            }
        }
        return preferred;
    }

    private boolean paragraphBoundary(int[] points, int end) {
        return (end >= 2 && points[end - 1] == '\n'
                && (points[end - 2] == '\n' || (end >= 4 && points[end - 2] == '\r'
                    && points[end - 3] == '\n' && points[end - 4] == '\r')))
                || (end < points.length && points[end - 1] == '\n' && points[end] == '#');
    }

    private boolean sentenceBoundary(int point) {
        return point == '。' || point == '！' || point == '？' || point == '.' || point == '!' || point == '?';
    }

    private String heading(int[] points, int start) {
        String latest = "";
        int cursor = 0;
        while (cursor <= start && cursor < points.length) {
            int end = cursor;
            while (end < points.length && points[end] != '\n') { end++; }
            int hashes = cursor;
            while (hashes < end && points[hashes] == '#') { hashes++; }
            if (hashes > cursor && hashes - cursor <= 6 && hashes < end && points[hashes] == ' ') {
                int contentStart = hashes + 1;
                int contentEnd = end > contentStart && points[end - 1] == '\r' ? end - 1 : end;
                if (contentEnd - contentStart <= 200) {
                    latest = new String(points, contentStart, contentEnd - contentStart);
                } else {
                    latest = ""; // 过长标题元数据不截断；正文仍完整保留。
                }
            }
            cursor = end + 1;
        }
        return latest;
    }
}
