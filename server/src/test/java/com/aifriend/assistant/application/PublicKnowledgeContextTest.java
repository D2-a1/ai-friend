package com.aifriend.assistant.application;

import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantConversation;
import com.aifriend.retrieval.domain.RetrievalQuery;

/** 全部合成问题，只验证有限检索扩展，不能当作通用语义准确率。 */
class PublicKnowledgeContextTest {
    @Test void explicitNewTopicPreservesOriginalQueryAndDoesNotUseOldSummary() {
        var query=query("联系人称呼怎么录制？");
        assertThat(PublicKnowledgeContext.resolve(query,history("如何开启守护"))).containsSame(query);
        assertThat(PublicKnowledgeContext.resolve(query("这个守护怎么关闭"),history("称呼怎么录制"))
                .orElseThrow().text()).isEqualTo("这个守护怎么关闭");
    }
    @Test void finiteFollowupKeepsOriginalWordsAndFrozenRetrievalScope() {
        var query=new RetrievalQuery("这个怎么关闭？","zh-CN",7,2,RetrievalQuery.ExternalProcessing.LOCAL_ONLY);
        var expanded=PublicKnowledgeContext.resolve(query,history("如何开启守护")).orElseThrow();
        assertThat(expanded.text()).isEqualTo("如何开启守护\n这个怎么关闭？");
        assertThat(expanded.locale()).isEqualTo("zh-CN");
        assertThat(expanded.appVersionCode()).isEqualTo(7);
        assertThat(expanded.limit()).isEqualTo(2);
        assertThat(expanded.externalProcessing()).isEqualTo(RetrievalQuery.ExternalProcessing.LOCAL_ONLY);
        assertThat(expanded.text()).doesNotContain("不得拿摘要作检索");
    }
    @Test void chainedFollowupsUseNearestExplicitQuestionNotOldTopic() {
        var expanded=PublicKnowledgeContext.resolve(query("那怎么关闭"),history("称呼怎么录制","守护是什么","它怎么开启"));
        assertThat(expanded.orElseThrow().text()).isEqualTo("守护是什么\n它怎么开启\n那怎么关闭");
    }
    @Test void barePluralUnresolvedAndMissingAnchorsClarifyInsteadOfGuessing() {
        for(String text:List.of("那个呢？","它","这两个怎么关闭","前者怎么设置","这些呢",
                "这个怎么退出","它能做什么","刚才说的那个呢","Can it do everything?")) {
            assertThat(PublicKnowledgeContext.resolve(query(text),history("守护是什么"))).isEmpty();
        }
        assertThat(PublicKnowledgeContext.resolve(query("怎么关闭"),history())).isEmpty();
        assertThat(PublicKnowledgeContext.resolve(query("怎么关闭"),history("如何开启守护和通知"))).isEmpty();
        assertThat(PublicKnowledgeContext.resolve(query("怎么关闭"),history("守护，消息通知"))).isEmpty();
        assertThat(PublicKnowledgeContext.resolve(query("怎么关闭"),history("如何开启守护","那个呢"))).isEmpty();
    }
    @Test void overflowCannotSilentlyTruncateAnchorOrQuestion() {
        assertThat(PublicKnowledgeContext.resolve(query("这个怎么关闭"),history("守护"+"甲".repeat(498)))).isEmpty();
        assertThat(PublicKnowledgeContext.resolve(query("这个怎么关闭"),history("守护"+"😀".repeat(495)))).isEmpty();
    }
    @Test void smallEnglishFollowupUsesSameBoundsAndNoModelRewrite() {
        assertThat(PublicKnowledgeContext.resolve(query("How do I disable it?"),history("How to enable guardian?"))
                .orElseThrow().text()).isEqualTo("How to enable guardian?\nHow do I disable it?");
        assertThat(PublicKnowledgeContext.resolve(query("this?"),history("How to enable guardian?"))).isEmpty();
    }
    @Test void graphHistoryIsNeverAcceptedEvenForExplicitPublicQuestion() {
        assertThatThrownBy(()->PublicKnowledgeContext.resolve(query("守护是什么"),new AssistantConversation(Purpose.CONTACT_GRAPH,List.of())))
                .hasMessage("PRIVATE_CONTEXT_FORBIDDEN");
    }
    private static RetrievalQuery query(String text) { return new RetrievalQuery(text,"zh-CN",1,4); }
    private static AssistantConversation history(String... questions) {
        var turns=new ArrayList<AssistantConversation.Turn>();
        for(int i=0;i<questions.length;i++) turns.add(new AssistantConversation.Turn(new UUID(0,i+1),i+1,questions[i],"不得拿摘要作检索"));
        return new AssistantConversation(Purpose.PUBLIC_KNOWLEDGE,turns);
    }
}
