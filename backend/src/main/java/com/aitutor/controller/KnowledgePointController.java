package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.service.KnowledgePointService;
import com.aitutor.vo.KnowledgePointVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge-points")
public class KnowledgePointController {

    private final KnowledgePointService knowledgePointService;

    public KnowledgePointController(KnowledgePointService knowledgePointService) {
        this.knowledgePointService = knowledgePointService;
    }

    @GetMapping("/tree")
    public Result<List<KnowledgePointVO>> listTree(@RequestParam(required = false) String subject) {
        return Result.success(knowledgePointService.listTree(subject));
    }
}
