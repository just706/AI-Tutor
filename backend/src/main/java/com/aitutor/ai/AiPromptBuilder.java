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

    public String buildQuestionGenerationPrompt(StudentProfile profile,
                                                KnowledgePoint knowledgePoint,
                                                String questionType,
                                                String difficulty,
                                                int count) {
        return """
                你是一个严谨的 AI 出题老师。
                请根据知识点生成练习题，并只输出合法 JSON，不要输出 Markdown 代码块或额外解释。

                知识点：%s
                所属学科：%s
                学生当前水平：%s
                学生学习目标：%s
                题目类型：%s
                难度：%s
                题目数量：%d

                JSON 格式必须为：
                {
                  "questions": [
                    {
                      "questionType": "%s",
                      "content": "题目内容",
                      "options": ["A. 选项一", "B. 选项二", "C. 选项三", "D. 选项四"],
                      "answer": "A",
                      "analysis": "答案解析",
                      "difficulty": "%s",
                      "knowledgePoint": "%s"
                    }
                  ]
                }

                要求：
                - 题目必须围绕当前知识点。
                - single_choice 必须有 4 个选项，answer 使用 A/B/C/D。
                - true_false 的 options 必须是 ["true", "false"]，answer 使用 true 或 false。
                - short_answer 的 options 使用空数组，answer 写参考答案。
                - analysis 必须解释为什么答案正确。
                - questions 数组数量必须等于题目数量。
                """.formatted(
                valueOrDefault(knowledgePoint == null ? null : knowledgePoint.getName()),
                valueOrDefault(knowledgePoint == null ? null : knowledgePoint.getSubject()),
                valueOrDefault(profile == null ? null : profile.getCurrentLevel()),
                valueOrDefault(profile == null ? null : profile.getLearningGoal()),
                valueOrDefault(questionType),
                valueOrDefault(difficulty),
                count,
                valueOrDefault(questionType),
                valueOrDefault(difficulty),
                valueOrDefault(knowledgePoint == null ? null : knowledgePoint.getName())
        );
    }

    public String buildSubjectiveAnswerPrompt(StudentProfile profile,
                                              KnowledgePoint knowledgePoint,
                                              String questionContent,
                                              String referenceAnswer,
                                              String studentAnswer) {
        return """
                你是一个负责批改简答题的 AI 教学老师。
                请根据题目、参考答案和学生回答给出评价。

                知识点：%s
                学生当前水平：%s
                题目：%s
                参考答案：%s
                学生回答：%s

                请严格按照以下结构输出：
                1. 得分：0-100
                2. 是否基本正确：是/否/部分正确
                3. 回答优点：
                4. 存在问题：
                5. 补充讲解：
                6. 下一步建议：

                要求：
                - 得分必须是 0 到 100 的整数。
                - 如果学生回答偏题或过短，得分应明显降低。
                - 反馈要具体，并与知识点相关。
                """.formatted(
                valueOrDefault(knowledgePoint == null ? null : knowledgePoint.getName()),
                valueOrDefault(profile == null ? null : profile.getCurrentLevel()),
                valueOrDefault(questionContent),
                valueOrDefault(referenceAnswer),
                valueOrDefault(studentAnswer)
        );
    }

    public String buildRagPrompt(String question, String sourceContext) {
        return """
                你是一个基于用户资料回答问题的 AI 学习助手。
                请优先且只基于【资料片段】回答用户问题。

                用户问题：%s

                【资料片段】
                %s

                回答要求：
                1. 如果资料片段足以回答，请用清晰、适合学习的方式回答。
                2. 回答中不要编造资料片段不存在的事实。
                3. 如果资料片段不足，请明确说明“资料中没有找到足够依据”，并建议用户补充资料或换个问法。
                4. 可以在回答末尾用“参考来源”简短列出片段编号。
                """.formatted(valueOrDefault(question), valueOrDefault(sourceContext));
    }

    private String valueOrDefault(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "未填写";
        }
        return value.trim();
    }
}
