package com.aitutor.ai;

import com.aitutor.exception.BusinessException;
import java.util.regex.Pattern;

/** 新备课的有限上下文检查；不重写题目，也不重新校验已保存的题库。 */
final class TutorQuestionContext {
    private static final String NUMBER = "[零〇一二三四五六七八九十百0-9]+";
    private static final Pattern LABEL = Pattern.compile(
            "^(?:第"+NUMBER+"(?:道)?题|(?i:question|q)\\s*\\d+)\\s*[:：.、]\\s*");
    private static final Pattern OTHER_QUESTION = Pattern.compile(
            "(?:上[一1]?|前[一1]?|下[一1]?|后[一1]?|前面|上面|之前|刚才|先前|其他)(?:道|个)?(?:题目?|问题)"
            + "|第"+NUMBER+"(?:道)?题|同上|同前"
            + "|(?i:\\b(?:previous|preceding|prior|earlier|last|next|following|another|other)\\s+(?:question|problem|exercise)s?\\b"
            + "|\\b(?:question|problem|exercise)\\s+(?:number\\s+)?[0-9]+\\b)");
    // 只拦截题干开头没有本题条件的常见回指。完整条件后使用“上述”不在此规则中拦截。
    private static final Pattern MISSING_PREFIX_CONTEXT = Pattern.compile(
            "^(?:(?:根据|依据|对于|在|沿用|使用|考虑|基于|继续|若沿用)\\s*)?"
            + "(?:上述|前述|上面的?|前面的?|刚才的?|以上的?)\\s*"
            + "(?:序列|操作|例子|示例|代码|函数|条件|数据|表格?|图形?|结果|情形|场景|案例|步骤|公式|规则)"
            + "|(?i:^(?:(?:using|given|consider|based on|after|for|in|with)\\s+)?(?:the\\s+)?"
            + "(?:above|previous|preceding|aforementioned|same)\\s+"
            + "(?:sequence|operations?|example|code|function|data|conditions?|table|diagram|result|scenario|formula)\\b)");

    private TutorQuestionContext() {}

    static void validate(TutorLearningFlow.Question question) {
        String prompt = LABEL.matcher(normalize(question.prompt())).replaceFirst("");
        if (OTHER_QUESTION.matcher(prompt).find() || MISSING_PREFIX_CONTEXT.matcher(prompt).find()) throw invalid();
        question.options().forEach(TutorQuestionContext::checkOtherQuestion);
        checkOtherQuestion(question.explanation());
        question.wrongOptionFeedback().forEach(f -> checkOtherQuestion(f.explanation()));
    }

    private static void checkOtherQuestion(String text) {
        if (OTHER_QUESTION.matcher(normalize(text)).find()) throw invalid();
    }

    private static String normalize(String text) {
        // 仅用于检测的视图；原始文本仍逐字保存。允许 Markdown 题号和跨行空白。
        return text.replaceAll("[*_`#>]", "").replaceAll("[\\s\\p{Z}]+", " ").trim();
    }

    private static BusinessException invalid() {
        return new BusinessException(600,"题目依赖其他题目或缺少上下文，请重新备课；原学习进度已保留。");
    }
}
