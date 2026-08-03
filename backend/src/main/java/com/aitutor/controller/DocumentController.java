package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.service.DocumentService;
import com.aitutor.vo.DocumentUploadVO;
import com.aitutor.vo.DocumentVO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<DocumentUploadVO> upload(@RequestPart("file") MultipartFile file) {
        return Result.success("上传成功", documentService.upload(file));
    }

    @GetMapping
    public Result<List<DocumentVO>> list() {
        return Result.success(documentService.listCurrentUserDocuments());
    }
}
