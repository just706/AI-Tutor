package com.aitutor.service;

import com.aitutor.vo.DocumentUploadVO;
import com.aitutor.vo.DocumentVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {

    DocumentUploadVO upload(MultipartFile file);

    List<DocumentVO> listCurrentUserDocuments();
}
