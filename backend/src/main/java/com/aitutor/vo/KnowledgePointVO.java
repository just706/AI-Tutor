package com.aitutor.vo;

import com.aitutor.entity.KnowledgePoint;

import java.util.ArrayList;
import java.util.List;

public class KnowledgePointVO {

    private Long id;
    private String subject;
    private String name;
    private Long parentId;
    private Integer sortOrder;
    private List<KnowledgePointVO> children = new ArrayList<>();

    public static KnowledgePointVO from(KnowledgePoint knowledgePoint) {
        KnowledgePointVO vo = new KnowledgePointVO();
        vo.setId(knowledgePoint.getId());
        vo.setSubject(knowledgePoint.getSubject());
        vo.setName(knowledgePoint.getName());
        vo.setParentId(knowledgePoint.getParentId());
        vo.setSortOrder(knowledgePoint.getSortOrder());
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public List<KnowledgePointVO> getChildren() {
        return children;
    }

    public void setChildren(List<KnowledgePointVO> children) {
        this.children = children;
    }
}
