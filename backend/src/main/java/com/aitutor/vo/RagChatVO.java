package com.aitutor.vo;

import java.util.List;

public class RagChatVO {

    private String requestId;
    private Integer attempt;
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer value) { attempt = value; }
    private Long conversationId;
    private String answer;
    private List<RagSourceVO> sources;

    public RagChatVO(Long conversationId, String answer, List<RagSourceVO> sources) {
        this.conversationId = conversationId;
        this.answer = answer;
        this.sources = sources;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<RagSourceVO> getSources() {
        return sources;
    }

    public void setSources(List<RagSourceVO> sources) {
        this.sources = sources;
    }
}
