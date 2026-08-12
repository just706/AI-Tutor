package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.service.LearnerMemoryService;
import com.aitutor.vo.LearnerMemoryVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/learner-memories")
public class LearnerMemoryController {

    private final LearnerMemoryService learnerMemoryService;

    public LearnerMemoryController(LearnerMemoryService learnerMemoryService) {
        this.learnerMemoryService = learnerMemoryService;
    }

    @GetMapping
    public Result<List<LearnerMemoryVO>> listMemories() {
        return Result.success(learnerMemoryService.getCurrentMemories());
    }

    @PatchMapping("/{memoryId}/suppress")
    public Result<Boolean> suppressMemory(@PathVariable Long memoryId) {
        return Result.success("已抑制该记忆", learnerMemoryService.suppressCurrentMemory(memoryId));
    }

    @DeleteMapping("/{memoryId}")
    public Result<Boolean> deleteMemory(@PathVariable Long memoryId) {
        return Result.success("已删除该记忆", learnerMemoryService.deleteCurrentMemory(memoryId));
    }
}
