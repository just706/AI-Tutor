package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.CreatePersonalGraphExtractionRequest;
import com.aitutor.service.PersonalGraphService;
import com.aitutor.vo.PersonalGraphExtractionVO;
import com.aitutor.vo.PersonalGraphVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/personal-graph")
public class PersonalGraphController {

    private final PersonalGraphService personalGraphService;

    public PersonalGraphController(PersonalGraphService personalGraphService) {
        this.personalGraphService = personalGraphService;
    }

    @PostMapping("/extractions")
    public Result<PersonalGraphExtractionVO> createExtraction(
            @Valid @RequestBody CreatePersonalGraphExtractionRequest request) {
        return Result.success(personalGraphService.createExtraction(request.getDocumentId()));
    }

    @GetMapping("/extractions/{extractionId}")
    public Result<PersonalGraphExtractionVO> getExtraction(@PathVariable Long extractionId) {
        return Result.success(personalGraphService.getExtraction(extractionId));
    }

    @GetMapping("/extractions")
    public Result<PersonalGraphExtractionVO> getLatestExtraction(@RequestParam Long documentId) {
        return Result.success(personalGraphService.getLatestExtraction(documentId));
    }

    @PostMapping("/extractions/{extractionId}/publish")
    public Result<PersonalGraphExtractionVO> publish(@PathVariable Long extractionId) {
        return Result.success(personalGraphService.publish(extractionId));
    }

    @GetMapping
    public Result<PersonalGraphVO> graph(@RequestParam(required = false) Long documentId) {
        return Result.success(personalGraphService.graph(documentId));
    }
}
