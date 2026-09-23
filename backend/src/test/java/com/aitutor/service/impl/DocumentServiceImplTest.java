package com.aitutor.service.impl;

import com.aitutor.config.RagProperties;
import com.aitutor.entity.DocumentChunk;
import com.aitutor.entity.LearningDocument;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.DocumentChunkMapper;
import com.aitutor.mapper.LearningDocumentMapper;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.aitutor.service.PersonalGraphService;
import com.aitutor.vo.DocumentUploadVO;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock
    private LearningDocumentMapper documentMapper;
    @Mock
    private DocumentChunkMapper documentChunkMapper;
    @Mock
    private PersonalGraphService personalGraphService;

    @TempDir
    Path tempDir;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void reprocessClearsTheExistingPersonalGraphBeforeReplacingChunks() throws IOException {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        LearningDocument document = storedDocument();
        when(documentMapper.selectOne(any())).thenReturn(document);

        service().reprocess(document.getId());

        InOrder order = inOrder(personalGraphService, documentChunkMapper);
        order.verify(personalGraphService).clearDocumentGraph(7L, document.getId());
        order.verify(documentChunkMapper).delete(any());
        verify(documentChunkMapper, atLeastOnce()).insert(any(DocumentChunk.class));
    }

    @Test
    void deleteClearsTheExistingPersonalGraphBeforeRemovingTheDocument() throws IOException {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        LearningDocument document = storedDocument();
        when(documentMapper.selectOne(any())).thenReturn(document);

        service().delete(document.getId());

        InOrder order = inOrder(personalGraphService, documentChunkMapper, documentMapper);
        order.verify(personalGraphService).clearDocumentGraph(7L, document.getId());
        order.verify(documentChunkMapper).delete(any());
        order.verify(documentMapper).deleteById(document.getId());
        assertFalse(Files.exists(Path.of(document.getStoragePath())));
    }

    @Test
    void blocksReprocessWhenPersonalGraphExtractionIsProcessing() throws IOException {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        LearningDocument document = storedDocument();
        when(documentMapper.selectOne(any())).thenReturn(document);
        doThrow(new BusinessException(409, "Personal graph extraction is still processing"))
                .when(personalGraphService).clearDocumentGraph(7L, document.getId());

        BusinessException exception = assertThrows(BusinessException.class, () -> service().reprocess(document.getId()));

        assertEquals(409, exception.getCode());
        verify(documentChunkMapper, never()).delete(any());
        verify(documentMapper, never()).updateById(any(LearningDocument.class));
    }

    private DocumentServiceImpl service() {
        RagProperties properties = new RagProperties();
        properties.setStorageDir(tempDir.toString());
        properties.setChunkSize(200);
        properties.setChunkOverlap(0);
        return new DocumentServiceImpl(documentMapper, documentChunkMapper, properties, personalGraphService);
    }

    @Test
    void longDocumentRemainsAvailableForRagWhenItExceedsGraphLimit() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        when(documentMapper.insert(any(LearningDocument.class))).thenAnswer(invocation -> {
            invocation.<LearningDocument>getArgument(0).setId(3001L);
            return 1;
        });
        lenient().when(personalGraphService.createExtraction(3001L))
                .thenThrow(new BusinessException(400, "Document has too many chunks; split it before graph extraction"));
        MockMultipartFile file = new MockMultipartFile("file", "large.txt", "text/plain",
                "资料内容".repeat(6000).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        DocumentUploadVO result = service().upload(file);

        assertEquals("completed", result.getProcessStatus());
        assertTrue(result.getChunkCount() > 100);
        assertNull(result.getPersonalGraphExtractionId());
        verify(personalGraphService, never()).createExtraction(any());
        verify(documentChunkMapper, atLeastOnce()).insert(any(DocumentChunk.class));
    }

    @Test
    void reprocessingLongDocumentDoesNotMarkParsedTextAsFailed() throws IOException {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        LearningDocument document = storedDocument();
        Files.writeString(Path.of(document.getStoragePath()), "资料内容".repeat(6000));
        when(documentMapper.selectOne(any())).thenReturn(document);
        lenient().when(personalGraphService.createExtraction(document.getId()))
                .thenThrow(new BusinessException(400, "Document has too many chunks; split it before graph extraction"));

        DocumentUploadVO result = service().reprocess(document.getId());

        assertEquals("completed", result.getProcessStatus());
        assertTrue(result.getChunkCount() > 100);
        assertNull(result.getPersonalGraphExtractionId());
        verify(personalGraphService, never()).createExtraction(any());
    }

    private LearningDocument storedDocument() throws IOException {
        Path userDir = tempDir.resolve("7");
        Files.createDirectories(userDir);
        Path file = userDir.resolve("notes.txt");
        Files.writeString(file, "可重处理的资料内容。".repeat(30));

        LearningDocument document = new LearningDocument();
        document.setId(3001L);
        document.setUserId(7L);
        document.setFileType("txt");
        document.setStoragePath(file.toString());
        document.setProcessStatus("completed");
        return document;
    }
}
