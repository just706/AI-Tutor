package com.aitutor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("knowledge_map_dependency")
public class KnowledgeMapDependency {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("prerequisite_point_id")
    private Long prerequisitePointId;
    @TableField("dependent_point_id")
    private Long dependentPointId;
    @TableField("relation_type")
    private String relationType;
    @TableField("relation_reason")
    private String relationReason;
    @TableField("sort_order")
    private Integer sortOrder;
    @TableField("create_time")
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPrerequisitePointId() {
        return prerequisitePointId;
    }

    public void setPrerequisitePointId(Long prerequisitePointId) {
        this.prerequisitePointId = prerequisitePointId;
    }

    public Long getDependentPointId() {
        return dependentPointId;
    }

    public void setDependentPointId(Long dependentPointId) {
        this.dependentPointId = dependentPointId;
    }

    public String getRelationType() {
        return relationType;
    }

    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }

    public String getRelationReason() {
        return relationReason;
    }

    public void setRelationReason(String relationReason) {
        this.relationReason = relationReason;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
