package com.aitutor.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RagFollowUpResolverTest {
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
