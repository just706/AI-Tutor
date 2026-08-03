package com.aitutor.ai;

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

    private String valueOrDefault(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "未填写";
        }
        return value.trim();
    }
}
