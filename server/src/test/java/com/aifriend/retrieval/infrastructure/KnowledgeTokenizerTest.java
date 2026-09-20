package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KnowledgeTokenizerTest {
    private final KnowledgeTokenizer tokenizer = new KnowledgeTokenizer();

    @Test void nfkcAndAsciiCaseAreAppliedToCopyOnly() {
        String original = "ＡＢＣ１２ 微信";
        assertThat(tokenizer.tokenize(original)).containsExactly("abc12", "微", "微信", "信");
        assertThat(original).isEqualTo("ＡＢＣ１２ 微信");
    }

    @Test void hanBigramsDoNotCrossPunctuationOrEmoji() {
        assertThat(tokenizer.tokenize("中，文😀界")).containsExactly("中", "文", "界");
        assertThat(tokenizer.tokenize("你好世界")).containsExactly("你", "你好", "好", "好世", "世", "世界", "界");
    }

    @Test void tfIsPreservedAndSupplementaryHanIsWhole() {
        String rare = new String(Character.toChars(0x20000));
        assertThat(tokenizer.tokenize("A a " + rare + "中")).containsExactly("a", "a", rare, rare + "中", "中");
    }

    @Test void emptySymbolsAreNotInventedTokensAndResultIsImmutable() {
        assertThat(tokenizer.tokenize(" \n!?😀")).isEmpty();
        assertThat(tokenizer.tokenize("")).isEmpty();
        assertThatThrownBy(() -> tokenizer.tokenize("hello").add("extra"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
