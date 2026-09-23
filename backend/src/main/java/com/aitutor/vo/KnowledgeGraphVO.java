package com.aitutor.vo;

import java.util.ArrayList;
import java.util.List;

public class KnowledgeGraphVO {

    private String subject;
    private List<KnowledgeGraphNodeVO> nodes = new ArrayList<>();
    private List<KnowledgeGraphEdgeVO> edges = new ArrayList<>();

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public List<KnowledgeGraphNodeVO> getNodes() {
        return nodes;
    }

    public void setNodes(List<KnowledgeGraphNodeVO> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }

    public List<KnowledgeGraphEdgeVO> getEdges() {
        return edges;
    }

    public void setEdges(List<KnowledgeGraphEdgeVO> edges) {
        this.edges = edges == null ? new ArrayList<>() : edges;
    }
}
