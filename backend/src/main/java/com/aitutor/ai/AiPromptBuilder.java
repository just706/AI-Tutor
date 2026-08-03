package com.aitutor.ai;

import com.aitutor.entity.KnowledgePoint;
import com.aitutor.entity.StudentProfile;
import org.springframework.stereotype.Component;

@Component
public class AiPromptBuilder {

    public String buildTutorPrompt(StudentProfile profile) {
        return """
                你是一个耐心、严谨的 AI 学习老师。
                请根据学生的背景和最近对话，用适合学生水平的方式回答问题。

                学生学习方向：%s
                学生学习目标：%s
                学生当前水平：%s
                学生学习偏好：%s

                回答要求：
                1. 先给出简明结论。
                2. 再解释核心概念。
                3. 尽量提供一个简单示例。
                4. 提醒常见错误。
                5. 给出下一步学习建议。
                6. 如果不确定，不要编造事实。
                """.formatted(
                valueOrDefault(profile == null ? null : profile.getLearningDirection()),
                valueOrDefault(profile == null ? null : profile.getLearningGoal()),
                valueOrDefault(profile == null ? null : profile.getCurrentLevel()),
                valueOrDefault(profile == null ? null : profile.getLearningPreference())
        );
    }

    public String buildTeachingPrompt(StudentProfile profile, KnowledgePoint knowledgePoint) {
        return """
                你是一个循序渐进的 AI 教学老师。
                当前教学知识点：%s
                所属学科：%s
                学生学习方向：%s
                学生学习目标：%s
                学生当前水平：%s
                学生学习偏好：%s

                请按照以下结构开始教学：
                1. 知识点一句话结论。
                2. 用通俗语言解释核心概念。
                3. 给出一个贴近日常或代码的简单例子。
                4. 提出一个理解检查问题，问题要清晰、可回答。
                5. 给出学习提醒。

                要求：
                - 不要一次性讲太多内容。
                - 适合基础学生循序渐进学习。
                - 不要提前给出理解检查问题的完整答案。
                - 如果知识点信息不足，说明合理假设。
                """.formatted(
                valueOrDefault(knowledgePoint == null ? null : knowledgePoint.getName()),
                valueOrDefault(knowledgePoint == null ? null : knowledgePoint.getSubject()),
                valueOrDefault(profile == null ? null : profile.getLearningDirection()),
                valueOrDefault(profile == null ? null : profile.getLearningGoal()),
                valueOrDefault(profile == null ? null : profile.getCurrentLevel()),
                valueOrDefault(profile == null ? null : profile.getLearningPreference())
        );
    }

    public String buildTeachingEvaluationPrompt(StudentProfile profile,
                                                KnowledgePoint knowledgePoint,
                                                String studentAnswer) {
        return """
                你是一个严格但鼓励学生的 AI 教学老师。
                当前知识点：%s
                所属学科：%s
                学生当前水平：%s
                学生学习偏好：%s
                学生回答：%s

                请根据最近的教学内容和学生回答进行评价，并严格按照以下结构输出：
                1. 是否基本正确：是/否/部分正确
                2. 得分：0-100
                3. 回答优点：
                4. 存在问题：
                5. 补充讲解：
                6. 下一步建议：

                要求：
                - 得分必须是 0 到 100 的整数。
                - 反馈要具体，避免空泛鼓励。
                - 如果学生回答太短或偏题，指出缺失内容并给一个可执行的改进建议。
                """.formatted(
                valueOrDefault(knowledgePoint == null ? null : knowledgePoint.getName()),
                valueOrDefault(knowledgePoint == null ? null : knowledgePoint.getSubject()),
                valueOrDefault(profile == null ? null : profile.getCurrentLevel()),
                valueOrDefault(profile == null ? null : profile.getLearningPreference()),
                valueOrDefault(studentAnswer)
        );
    }

    private String valueOrDefault(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "未填写";
        }
        return value.trim();
    }
}
