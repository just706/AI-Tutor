package com.aitutor.vo;

import java.util.ArrayList;
import java.util.List;

public class PersonalGraphCandidatesVO {

    private List<PersonalGraphCandidateNodeVO> nodes = new ArrayList<>();
    private List<PersonalGraphCandidateEdgeVO> edges = new ArrayList<>();

    public List<PersonalGraphCandidateNodeVO> getNodes() { return nodes; }
    public void setNodes(List<PersonalGraphCandidateNodeVO> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }
    public List<PersonalGraphCandidateEdgeVO> getEdges() { return edges; }
    public void setEdges(List<PersonalGraphCandidateEdgeVO> edges) {
        this.edges = edges == null ? new ArrayList<>() : edges;
    }
}
