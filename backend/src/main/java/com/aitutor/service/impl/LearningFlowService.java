package com.aitutor.service.impl;

import com.aitutor.ai.TutorLearningFlow;
import com.aitutor.dto.TutorAgentChatRequest;
import com.aitutor.entity.LearningSession;
import com.aitutor.entity.LearningSessionStep;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.LearningSessionMapper;
import com.aitutor.mapper.LearningSessionStepMapper;
import com.aitutor.service.AiChatService;
import com.aitutor.vo.LearningSessionVO;
import com.aitutor.vo.TutorAgentChatVO;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/** 由外层教师事务和会话行锁保护；模型不拥有状态迁移权限。 */
@Service
public class LearningFlowService {
    private final AiChatService chat;
    private final LearningSessionMapper sessions;
    private final LearningSessionStepMapper steps;
    public LearningFlowService(AiChatService chat, LearningSessionMapper sessions, LearningSessionStepMapper steps) {
        this.chat=chat; this.sessions=sessions; this.steps=steps;
    }

    public TutorAgentChatVO advance(LearningSession session, TutorAgentChatRequest request, String detectedTopic) {
        var state=TutorLearningFlow.read(session.getFlowState());
        String action=request.getFlowAction();
        if (state == null) {
            if (!"start".equals(action)) throw new BusinessException(400, "请先开始循序学习。");
            String topic=detectedTopic == null || detectedTopic.isBlank() ? request.getMessage().trim() : detectedTopic;
            if (topic.length() > 200) throw new BusinessException(400, "请先填写一个简短的学习主题，再补充资料。");
            state=TutorLearningFlow.clarify(topic);
        } else {
            if (request.getFlowVersion() == null || request.getFlowVersion() != state.version())
                throw new BusinessException(409, "学习进度已更新，请刷新后继续。");
            if (action == null) action="CLARIFYING".equals(state.stage()) ? "clarify" : "answer";
            if ("clarify".equals(action) && "CLARIFYING".equals(state.stage())) {
                String source=request.getMessage().trim();
                if (source.length() < 15) throw new BusinessException(400, "请补充所属领域与具体定义或资料片段，仅确认名称不足以备课。");
                var lesson=chat.generateLesson(state.topic(),source);
                var begun=TutorLearningFlow.begin(state.topic(),source,lesson);
                state=new TutorLearningFlow.State(state.version()+1,begun.stage(),begun.topic(),source,lesson,0,0,List.of(),"");
            } else state=TutorLearningFlow.advance(state,state.version(),action,request.getMessage());
        }
        String previous=session.getStatus();
        session.setTopic(state.topic()); session.setGoal("循序学习："+state.topic()); session.setIntent("learn");
        session.setCurrentStepType(state.stage().toLowerCase(java.util.Locale.ROOT));
        session.setStatus(switch(state.stage()) {
            case "CLARIFYING", "DIAGNOSING", "CHECKING" -> "DIAGNOSING";
            case "TEACHING", "RETEACHING" -> "TEACHING";
            case "PRACTICE" -> "PRACTICE";
            default -> "REFLECTION";
        });
        session.setTeachingStrategy(switch(state.stage()) {
            case "PRACTICE" -> "practice_first";
            case "RETEACHING" -> "prerequisite_first";
            case "FEEDBACK", "REVIEW" -> "summary_review";
            default -> "concept_first";
        });
        session.setNextAction(TutorLearningFlow.view(state).canContinue() ? "阅读后点击理解检查。"
                : "CLARIFYING".equals(state.stage()) ? "补充主题所属领域和参考资料。"
                : List.of("FEEDBACK","REVIEW").contains(state.stage()) ? "回顾本轮记录，结束会话或开始新的学习主题。" : "完成当前选择题后继续。");
        session.setFlowState(TutorLearningFlow.write(state)); session.setUpdateTime(LocalDateTime.now());
        sessions.updateById(session);
        String answer=TutorLearningFlow.render(state);
        chat.recordExchange(session.getConversationId(),request.getMessage(),answer);
        LearningSessionStep step=new LearningSessionStep();
        step.setSessionId(session.getId()); step.setUserId(session.getUserId()); step.setConversationId(session.getConversationId());
        step.setStatusFrom(previous); step.setStatusTo(session.getStatus()); step.setStepType(session.getCurrentStepType());
        step.setIntent("learn"); step.setTeachingStrategy(session.getTeachingStrategy());
        step.setUserMessage(request.getMessage()); step.setAgentResponse(answer);
        step.setStrategySource("[\"循序学习：按当前步骤和选择题作答推进，不据此更新掌握度\"]"); step.setActionsSnapshot("[]");
        steps.insert(step);
        var result=new TutorAgentChatVO(); result.setAnswer(answer); result.setIntent("learn");
        var vo=LearningSessionVO.from(session);
        var reasons=List.of("循序学习：按当前步骤和选择题作答推进，不据此更新掌握度");
        vo.setStrategySource(reasons); result.setLearningSession(vo); result.setStrategySource(reasons);
        result.setTeachingStrategy(session.getTeachingStrategy());
        result.setToolTraces(List.of("guided_learning_step_saved"));
        return result;
    }
}
