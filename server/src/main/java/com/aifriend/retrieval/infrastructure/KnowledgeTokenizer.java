package com.aifriend.retrieval.infrastructure;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import com.aifriend.retrieval.domain.KnowledgeText;

/**
 * 查询/文档共用的确定性分词：NFKC副本、ASCII小写词、汉字单字及相邻双字。
 * @author codex
 * @since 1.0.0
 */
public final class KnowledgeTokenizer {
    /** 规范化或分词口径变更必须提升版本并重建索引。 */
    public static final String VERSION = "nfkc-han12-ascii-v1";

    /** 创建无状态分词器。 */
    public KnowledgeTokenizer() { }

    /**
     * 不修改原文；空白/符号得到空词表，不生成任意猜测词。
     * @param text 最多600码点
     * @return 保留tf的不可变token列表
     */
    public List<String> tokenize(String text) {
        KnowledgeText.require(text, 600, 2400, true);
        int[] points = Normalizer.normalize(text, Normalizer.Form.NFKC).codePoints().toArray();
        var tokens = new ArrayList<String>();
        for (int i = 0; i < points.length;) {
            int point = points[i];
            if (Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN) {
                tokens.add(new String(Character.toChars(point)));
                if (i + 1 < points.length
                        && Character.UnicodeScript.of(points[i + 1]) == Character.UnicodeScript.HAN) {
                    tokens.add(new String(points, i, 2));
                }
                i++;
            } else if (asciiWord(point)) {
                var word = new StringBuilder();
                do {
                    point = points[i++];
                    word.appendCodePoint(point >= 'A' && point <= 'Z' ? point + ('a' - 'A') : point);
                } while (i < points.length && asciiWord(points[i]));
                tokens.add(word.toString());
            } else {
                i++;
            }
        }
        return List.copyOf(tokens);
    }

    private boolean asciiWord(int point) {
        return (point >= 'A' && point <= 'Z') || (point >= 'a' && point <= 'z') || (point >= '0' && point <= '9');
    }
}
