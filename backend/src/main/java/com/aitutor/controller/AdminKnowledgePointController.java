package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.KnowledgePointRequest;
import com.aitutor.service.KnowledgePointService;
import com.aitutor.vo.KnowledgePointVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/knowledge-points")
public class AdminKnowledgePointController {

    private final KnowledgePointService knowledgePointService;

    public AdminKnowledgePointController(KnowledgePointService knowledgePointService) {
        this.knowledgePointService = knowledgePointService;
    }

    @PostMapping
    public Result<KnowledgePointVO> create(@Valid @RequestBody KnowledgePointRequest request) {
        return Result.success(knowledgePointService.create(request));
    }

    @PutMapping("/{id}")
    public Result<KnowledgePointVO> update(@PathVariable Long id,
                                           @Valid @RequestBody KnowledgePointRequest request) {
        return Result.success(knowledgePointService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(knowledgePointService.delete(id));
    }
}
