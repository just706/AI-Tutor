package com.aitutor.ai;

import com.aitutor.vo.KnowledgeMapContextVO;
import java.util.List;

/** 服务端在生成回答前确定的教学上下文，不从客户端请求绑定。 */
public record TutorTurnContext(String intent, String topic, String teachingStrategy,
                               String nextAction, List<String> strategySource,
                               KnowledgeMapContextVO knowledgeMap) {
    public TutorTurnContext {
        strategySource = strategySource == null ? List.of() : List.copyOf(strategySource);
    }
}
