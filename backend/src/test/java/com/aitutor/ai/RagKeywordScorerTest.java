package com.aitutor.ai;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class RagKeywordScorerTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "导数和积分有什么区别？|导数表示局部变化率。积分表示累积量。",
            "平均速度和瞬时速度有什么区别？|平均速度表示总位移与总时间的比值。瞬时速度描述某一时刻的运动状态。",
            "机会成本与沉没成本有什么区别？|机会成本是放弃的最佳替代选择的价值。沉没成本是已经发生且无法收回的支出。",
            "星云投影和月影映射有什么区别？|星云投影表示一种合成关系。月影映射表示另一种合成关系。",
            "ArrayList 和 LinkedList 有什么区别？|ArrayList 基于动态数组。LinkedList 基于双向链表。"
    })
    void generalComparisonsRetrieveDescriptionsRegardlessOfTopic(String question, String text) {
        assertTrue(new RagKeywordScorer(question, List.of(text)).score(text) > 0);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "导数和积分有什么区别？|本章列出导数和积分。",
            "平均速度和瞬时速度有什么区别？|平均速度表示总位移与总时间的比值。",
            "导数的用途是什么？|积分的用途是计算累积量。",
            "牛顿第二定律的发现年份是多少？|牛顿第二定律描述力和加速度的关系。",
            "平均速度和瞬时速度在测量误差方面有什么区别？|平均速度表示总位移与总时间的比值。瞬时速度描述某一时刻的运动状态。"
    })
    void namesAloneOrAWordSharedWithAnotherTopicDoNotSupportTheQuestion(String question, String text) {
        assertEquals(0, new RagKeywordScorer(question, List.of(text)).score(text));
    }

    @Test void theQuestionSubjectIsNotRemovedFromTheRanking() {
        String direct = "导数与积分的区别：导数表示局部变化率，积分表示累积量。";
        String fallback = "导数表示局部变化率。积分表示累积量。";
        var scorer = new RagKeywordScorer("导数和积分有什么区别？", List.of(direct, fallback));
        assertTrue(scorer.score(direct) > scorer.score(fallback));
    }
}
