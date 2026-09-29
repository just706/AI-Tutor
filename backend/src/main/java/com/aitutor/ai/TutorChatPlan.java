package com.aitutor.ai;

import com.aitutor.vo.KnowledgePointVO;
import com.aitutor.vo.OrchestratorActionVO;
import java.util.List;

/** 规则识别结果；准备计划不调用模型、不保存聊天消息。 */
public record TutorChatPlan(String intent, KnowledgePointVO matchedKnowledgePoint,
                            List<OrchestratorActionVO> actions) {
    public TutorChatPlan {
        actions = List.copyOf(actions);
    }
}
