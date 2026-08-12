package com.aitutor.service.impl;

import com.aitutor.entity.Conversation;
import com.aitutor.entity.LearningSession;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.ConversationMapper;
import com.aitutor.mapper.LearningSessionMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.LearningSessionService;
import com.aitutor.vo.LearningSessionVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LearningSessionServiceImpl implements LearningSessionService {

    private static final List<String> ACTIVE_STATUSES = List.of(
            "CREATED",
            "DIAGNOSING",
            "PLANNING",
            "TEACHING",
            "PRACTICE",
            "REFLECTION"
    );

    private final ConversationMapper conversationMapper;
    private final LearningSessionMapper learningSessionMapper;

    public LearningSessionServiceImpl(ConversationMapper conversationMapper,
                                      LearningSessionMapper learningSessionMapper) {
        this.conversationMapper = conversationMapper;
        this.learningSessionMapper = learningSessionMapper;
    }

    @Override
    public LearningSessionVO getActiveSession(Long conversationId) {
        Long userId = UserContext.getRequired().getId();
        requireOwnedConversation(userId, conversationId);

        LearningSession session = learningSessionMapper.selectOne(new LambdaQueryWrapper<LearningSession>()
                .eq(LearningSession::getUserId, userId)
                .eq(LearningSession::getConversationId, conversationId)
                .in(LearningSession::getStatus, ACTIVE_STATUSES)
                .orderByDesc(LearningSession::getUpdateTime)
                .orderByDesc(LearningSession::getId)
                .last("LIMIT 1"));
        return LearningSessionVO.from(session);
    }

    private void requireOwnedConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .last("LIMIT 1"));
        if (conversation == null) {
            throw new BusinessException(404, "Conversation not found");
        }
    }
}
