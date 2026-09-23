package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.service.KnowledgeMapService;
import com.aitutor.vo.KnowledgeGraphVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-map")
public class KnowledgeMapController {

    private final KnowledgeMapService knowledgeMapService;

    public KnowledgeMapController(KnowledgeMapService knowledgeMapService) {
        this.knowledgeMapService = knowledgeMapService;
    }

    @GetMapping("/graph")
    public Result<KnowledgeGraphVO> graph(@RequestParam(required = false) String subject) {
        return Result.success(knowledgeMapService.graph(subject));
    }
}
