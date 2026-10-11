package com.aitutor.service;

import com.aitutor.entity.LearningSession;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.LearningSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/** 调用前须在事务内锁定会话，和循序学习开始、结束使用同一把行锁。 */
@Service
public class LearningFlowGuard {
    private final LearningSessionMapper sessions;

    public LearningFlowGuard(LearningSessionMapper sessions) { this.sessions = sessions; }

    public void requireNoActiveFlow(Long userId, Long conversationId) {
        if (sessions.selectCount(new LambdaQueryWrapper<LearningSession>()
                .eq(LearningSession::getUserId, userId)
                .eq(LearningSession::getConversationId, conversationId)
                .ne(LearningSession::getStatus, "COMPLETED")
                .isNotNull(LearningSession::getFlowState)) > 0)
            throw new BusinessException(409, "请先结束本轮循序学习，再更换教材或进行教材问答。");
    }
}
