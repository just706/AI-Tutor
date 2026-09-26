package com.aitutor.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RagFollowUpResolverTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "什么是导数？|它有什么用途？|导数有什么用途？",
            "请解释牛顿第二定律|它的适用条件是什么？|牛顿第二定律的适用条件是什么？",
            "介绍光合作用|这个概念有什么特点？|光合作用有什么特点？",
            "机会成本是什么？|它有什么用途？|机会成本有什么用途？",
            "解释星云投影|它有什么特点？|星云投影有什么特点？",
            "Explain opportunity cost|它有什么用途？|opportunity cost有什么用途？",
            "What is Newton's second law?|它的适用条件是什么？|Newton's second law的适用条件是什么？",
            "解释κ统计量|它有什么用途？|κ统计量有什么用途？"
    })
    void topicsComeFromQuestionPhrasesInsteadOfADisciplineDictionary(String previous, String followUp, String expected) {
        var result = RagFollowUpResolver.resolve(followUp, List.of(previous));
        assertNull(result.clarification());
        assertEquals(expected, result.question());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "导数和积分有什么区别？|那两个概念有什么区别？|导数 和 积分有什么区别？",
            "平均速度与瞬时速度有什么区别？|后者有什么特点？|瞬时速度有什么特点？",
            "介绍机会成本和沉没成本|它们有什么区别？|机会成本 和 沉没成本有什么区别？"
    })
    void orderedPairsWorkAcrossSubjects(String previous, String followUp, String expected) {
        var result = RagFollowUpResolver.resolve(followUp, List.of(previous));
        assertNull(result.clarification());
        assertEquals(expected, result.question());
    }

    @Test void switchingSubjectsAcrossDisciplinesReplacesTheOldTopic() {
        var result = RagFollowUpResolver.resolve("它的适用条件是什么？", List.of("牛顿第二定律是什么？", "什么是导数？"));
        assertEquals("牛顿第二定律的适用条件是什么？", result.question());
    }

    @Test void aChineseNewNameAlongsideAReferenceStillNeedsClarification() {
        assertNotNull(RagFollowUpResolver.resolve("积分和它有什么区别？", List.of("什么是导数？")).clarification());
    }

    @Test void anUnsupportedMultiClauseQuestionDoesNotReuseAnOldTopic() {
        assertNotNull(RagFollowUpResolver.resolve("它有什么特点？", List.of("先解释导数，再解释积分", "解释 ArrayList")).clarification());
    }

    @ParameterizedTest
    @CsvSource({"前者有什么缺点？,ArrayList有什么缺点？", "后者是什么结构？,LinkedList是什么结构？", "它们有什么区别？,ArrayList 和 LinkedList有什么区别？"})
    void orderedReferencesUseTheExplicitPair(String question, String expected) {
        var result = RagFollowUpResolver.resolve(question, List.of("ArrayList 和 LinkedList 有什么区别？"));
        assertNull(result.clarification());
        assertEquals(expected, result.question());
    }

    @Test void aChainOfPronounsRetainsTheRecentExplicitTopic() {
        var result = RagFollowUpResolver.resolve("它的缺点呢？", List.of("它的随机访问速度如何？", "解释 ArrayList"));
        assertEquals("ArrayList的缺点呢？", result.question());
    }

    @Test void aNewExplicitTopicReplacesTheOldOne() {
        var result = RagFollowUpResolver.resolve("它的缺点呢？", List.of("解释 HashMap", "解释 ArrayList"));
        assertEquals("HashMap的缺点呢？", result.question());
    }

    @Test void anUnrecognizedNewTopicDoesNotFallBackToAnOlderClass() {
        var result = RagFollowUpResolver.resolve("它有什么特点？", List.of("今天学习计算机组成原理", "解释 ArrayList"));
        assertNotNull(result.clarification());
    }

    @Test void nestedChineseTermsDoNotInventMultipleSubjects() {
        var result = RagFollowUpResolver.resolve("它有什么特点？", List.of("解释双向链表"));
        assertEquals("双向链表有什么特点？", result.question());
    }

    @Test void anExplicitNewQuestionDoesNotAcquireThePreviousTopic() {
        var result = RagFollowUpResolver.resolve("HashMap 的扩容机制是什么？", List.of("解释 ArrayList"));
        assertEquals("HashMap 的扩容机制是什么？", result.question());
        assertNull(result.clarification());
    }

    @Test void pluralReferenceDoesNotInventASecondTopic() {
        assertNotNull(RagFollowUpResolver.resolve("那两种有什么区别？", List.of("解释 ArrayList")).clarification());
    }

    @Test void mixedExplicitAndImplicitSubjectsAskForClarification() {
        assertNotNull(RagFollowUpResolver.resolve("HashMap 和它有什么区别？", List.of("ArrayList 和 LinkedList")).clarification());
    }

    @Test void otherInChineseIsNotMistakenForAPronoun() {
        assertNull(RagFollowUpResolver.resolve("其它集合有什么特点？", List.of()).clarification());
    }

    @Test void aCollectionWordInsideTheReferenceIsNotANewExplicitSubject() {
        var result = RagFollowUpResolver.resolve("那两种集合有什么区别？", List.of("介绍 ArrayList 和 LinkedList"));
        assertEquals("ArrayList 和 LinkedList有什么区别？", result.question());
        assertNull(result.clarification());
    }

    @Test void aPronounAfterTheLatterUsesOnlyTheLatterTopic() {
        var result = RagFollowUpResolver.resolve("它有什么缺点？", List.of("后者是什么结构？", "ArrayList 和 LinkedList"));
        assertEquals("LinkedList有什么缺点？", result.question());
    }

    @Test void aTopicOutsideTheContextWindowCannotResolveAReference() {
        var history = new java.util.ArrayList<>(java.util.Collections.nCopies(10, "它是什么？"));
        history.add("解释 ArrayList");
        assertNotNull(RagFollowUpResolver.resolve("它的缺点呢？", history).clarification());
    }
}
