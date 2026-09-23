package com.aitutor.vo;

import java.util.ArrayList;
import java.util.List;

public class PersonalGraphVO {

    private List<PersonalGraphNodeVO> nodes = new ArrayList<>();
    private List<PersonalGraphEdgeVO> edges = new ArrayList<>();

    public List<PersonalGraphNodeVO> getNodes() { return nodes; }
    public void setNodes(List<PersonalGraphNodeVO> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }
    public List<PersonalGraphEdgeVO> getEdges() { return edges; }
    public void setEdges(List<PersonalGraphEdgeVO> edges) {
        this.edges = edges == null ? new ArrayList<>() : edges;
    }
}
