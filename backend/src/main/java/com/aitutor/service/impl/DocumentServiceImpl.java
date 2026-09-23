package com.aitutor.service.impl;

import com.aitutor.config.RagProperties;
import com.aitutor.entity.DocumentChunk;
import com.aitutor.entity.LearningDocument;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.DocumentChunkMapper;
import com.aitutor.mapper.LearningDocumentMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.DocumentService;
import com.aitutor.service.PersonalGraphService;
import com.aitutor.vo.DocumentChunkVO;
import com.aitutor.vo.DocumentDetailVO;
import com.aitutor.vo.DocumentUploadVO;
import com.aitutor.vo.DocumentVO;
import com.aitutor.vo.PersonalGraphExtractionVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentServiceImpl implements DocumentService {

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";
    private static final String EMBEDDING_PLACEHOLDER = "mysql-keyword";
    private static final Set<String> SUPPORTED_TYPES = Set.of("txt", "md", "markdown", "pdf", "doc", "docx");

    private final LearningDocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final RagProperties ragProperties;
    private final PersonalGraphService personalGraphService;

    @Autowired
    public DocumentServiceImpl(LearningDocumentMapper documentMapper,
                               DocumentChunkMapper documentChunkMapper,
                               RagProperties ragProperties,
                               PersonalGraphService personalGraphService) {
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.ragProperties = ragProperties;
        this.personalGraphService = personalGraphService;
    }

    /** Keeps lightweight service tests and legacy integrations source-compatible. */
    public DocumentServiceImpl(LearningDocumentMapper documentMapper,
                               DocumentChunkMapper documentChunkMapper,
                               RagProperties ragProperties) {
        this(documentMapper, documentChunkMapper, ragProperties, null);
    }

    @Override
    @Transactional
    public DocumentUploadVO upload(MultipartFile file) {
        Long userId = UserContext.getRequired().getId();
        validateFile(file);

        String originalFileName = sanitizeFileName(file.getOriginalFilename());
        String fileType = fileType(originalFileName);
        byte[] fileBytes = readFileBytes(file);
        Path storagePath = saveFile(userId, fileBytes, originalFileName, fileType);

        LearningDocument document = new LearningDocument();
        document.setUserId(userId);
        document.setFileName(originalFileName);
        document.setFileType(fileType);
        document.setStoragePath(storagePath.toString());
        document.setProcessStatus(STATUS_PENDING);
        documentMapper.insert(document);

        try {
            String text = parseText(fileBytes, fileType);
            List<String> chunks = splitIntoChunks(text);
            if (chunks.isEmpty()) {
                throw new BusinessException(400, "Document content is empty");
            }
            saveChunks(document.getId(), chunks);
            updateStatus(document.getId(), STATUS_COMPLETED);
            PersonalGraphExtractionVO extraction = startAutomaticGraph(document.getId(), chunks.size());
            return new DocumentUploadVO(document.getId(), STATUS_COMPLETED, chunks.size(),
                    extraction == null ? null : extraction.getId());
        } catch (RuntimeException ex) {
            updateStatus(document.getId(), STATUS_FAILED);
            throw ex;
        }
    }

    @Override
    public List<DocumentVO> listCurrentUserDocuments() {
        Long userId = UserContext.getRequired().getId();
        return documentMapper.selectList(new LambdaQueryWrapper<LearningDocument>()
                        .eq(LearningDocument::getUserId, userId)
                        .orderByDesc(LearningDocument::getUploadTime)
                        .orderByDesc(LearningDocument::getId))
                .stream()
                .map(document -> DocumentVO.from(document, countChunks(document.getId())))
                .toList();
    }

    @Override
    public DocumentDetailVO getCurrentUserDocument(Long documentId) {
        Long userId = UserContext.getRequired().getId();
        LearningDocument document = requireOwnedDocument(userId, documentId);
        List<DocumentChunk> chunks = listChunks(document.getId());
        return DocumentDetailVO.from(document, chunks.size(), preview(chunks));
    }

    @Override
    public List<DocumentChunkVO> listCurrentUserDocumentChunks(Long documentId) {
        Long userId = UserContext.getRequired().getId();
        LearningDocument document = requireOwnedDocument(userId, documentId);
        return listChunks(document.getId()).stream()
                .map(DocumentChunkVO::from)
                .toList();
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public DocumentUploadVO reprocess(Long documentId) {
        Long userId = UserContext.getRequired().getId();
        LearningDocument document = requireOwnedDocument(userId, documentId);
        Path path = safeStoragePath(document);
        if (!Files.exists(path)) {
            throw new BusinessException(404, "Document file not found");
        }

        if (personalGraphService != null) {
            personalGraphService.clearDocumentGraph(userId, document.getId());
        }
        updateStatus(document.getId(), STATUS_PENDING);
        deleteChunks(document.getId());
        try {
            byte[] fileBytes = Files.readAllBytes(path);
            String text = parseText(fileBytes, document.getFileType());
            List<String> chunks = splitIntoChunks(text);
            if (chunks.isEmpty()) {
                throw new BusinessException(400, "Document content is empty");
            }
            saveChunks(document.getId(), chunks);
            updateStatus(document.getId(), STATUS_COMPLETED);
            PersonalGraphExtractionVO extraction = startAutomaticGraph(document.getId(), chunks.size());
            return new DocumentUploadVO(document.getId(), STATUS_COMPLETED, chunks.size(),
                    extraction == null ? null : extraction.getId());
        } catch (IOException ex) {
            updateStatus(document.getId(), STATUS_FAILED);
            throw new BusinessException(500, "Failed to read document file");
        } catch (RuntimeException ex) {
            updateStatus(document.getId(), STATUS_FAILED);
            throw ex;
        }
    }

    @Override
    @Transactional
    public void delete(Long documentId) {
        Long userId = UserContext.getRequired().getId();
        LearningDocument document = requireOwnedDocument(userId, documentId);
        if (personalGraphService != null) {
            personalGraphService.clearDocumentGraph(userId, document.getId());
        }
        deleteChunks(document.getId());
        documentMapper.deleteById(document.getId());
        deleteStoredFile(document);
    }

    private PersonalGraphExtractionVO startAutomaticGraph(Long documentId, int chunkCount) {
        // Graph generation is optional; its limit must not reject a valid RAG document.
        if (personalGraphService == null || chunkCount > PersonalGraphService.MAX_EXTRACTION_CHUNKS) {
            return null;
        }
        return personalGraphService.createExtraction(documentId);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "Document file is required");
        }
        Long maxFileBytes = ragProperties.getMaxFileBytes();
        if (maxFileBytes != null && maxFileBytes > 0 && file.getSize() > maxFileBytes) {
            throw new BusinessException(400, "Document file is too large");
        }

        String originalFileName = sanitizeFileName(file.getOriginalFilename());
        String fileType = fileType(originalFileName);
        if (!SUPPORTED_TYPES.contains(fileType)) {
            throw new BusinessException(400, "Unsupported document type");
        }
    }

    private byte[] readFileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(500, "Failed to read document file");
        }
    }

    private Path saveFile(Long userId, byte[] fileBytes, String originalFileName, String fileType) {
        try {
            Path baseDir = Paths.get(ragProperties.getStorageDir()).toAbsolutePath().normalize();
            Path userDir = baseDir.resolve(String.valueOf(userId)).normalize();
            Files.createDirectories(userDir);

            String storedFileName = UUID.randomUUID() + "-" + originalFileNameWithoutExtension(originalFileName) + "." + fileType;
            Path target = userDir.resolve(storedFileName).normalize();
            if (!target.startsWith(userDir)) {
                throw new BusinessException(400, "Invalid document file name");
            }
            Files.write(target, fileBytes);
            return target;
        } catch (IOException ex) {
            throw new BusinessException(500, "Failed to save document file");
        }
    }

    private String parseText(byte[] fileBytes, String fileType) {
        if ("pdf".equals(fileType)) {
            return parsePdfText(fileBytes);
        }
        if ("docx".equals(fileType)) {
            return parseDocxText(fileBytes);
        }
        if ("doc".equals(fileType)) {
            return parseDocText(fileBytes);
        }
        String text = new String(fileBytes, StandardCharsets.UTF_8);
        return normalizeText(text);
    }

    private String parseDocxText(byte[] fileBytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return normalizeText(extractor.getText());
        } catch (IOException ex) {
            throw new BusinessException(400, "Failed to parse DOCX document");
        }
    }

    private String parseDocText(byte[] fileBytes) {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(fileBytes));
             WordExtractor extractor = new WordExtractor(document)) {
            return normalizeText(extractor.getText());
        } catch (IOException ex) {
            throw new BusinessException(400, "Failed to parse DOC document");
        }
    }

    private String parsePdfText(byte[] fileBytes) {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(fileBytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            return normalizeText(stripper.getText(document));
        } catch (IOException ex) {
            throw new BusinessException(400, "Failed to parse PDF document");
        }
    }

    private List<String> splitIntoChunks(String text) {
        int chunkSize = normalizedChunkSize();
        int overlap = normalizedOverlap(chunkSize);
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end == text.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    private void saveChunks(Long documentId, List<String> chunks) {
        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkText(chunks.get(index));
            chunk.setChunkIndex(index);
            // Placeholder keeps the schema compatible with a later vector-database migration.
            chunk.setEmbeddingId(EMBEDDING_PLACEHOLDER);
            documentChunkMapper.insert(chunk);
        }
    }

    private Integer countChunks(Long documentId) {
        return documentChunkMapper.selectCount(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId)).intValue();
    }

    private List<DocumentChunk> listChunks(Long documentId) {
        return documentChunkMapper.selectList(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId)
                .orderByAsc(DocumentChunk::getChunkIndex)
                .orderByAsc(DocumentChunk::getId));
    }

    private void deleteChunks(Long documentId) {
        documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId));
    }

    private LearningDocument requireOwnedDocument(Long userId, Long documentId) {
        LearningDocument document = documentMapper.selectOne(new LambdaQueryWrapper<LearningDocument>()
                .eq(LearningDocument::getId, documentId)
                .eq(LearningDocument::getUserId, userId)
                .last("LIMIT 1"));
        if (document == null) {
            throw new BusinessException(404, "Document not found");
        }
        return document;
    }

    private String preview(List<DocumentChunk> chunks) {
        if (chunks.isEmpty() || chunks.get(0).getChunkText() == null) {
            return "";
        }
        String normalized = chunks.get(0).getChunkText().replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 300) {
            return normalized;
        }
        return normalized.substring(0, 300);
    }

    private Path safeStoragePath(LearningDocument document) {
        Path storagePath = Paths.get(document.getStoragePath()).toAbsolutePath().normalize();
        Path baseDir = Paths.get(ragProperties.getStorageDir()).toAbsolutePath().normalize();
        if (!storagePath.startsWith(baseDir)) {
            throw new BusinessException(400, "Invalid document storage path");
        }
        return storagePath;
    }

    private void deleteStoredFile(LearningDocument document) {
        try {
            Files.deleteIfExists(safeStoragePath(document));
        } catch (IOException ex) {
            throw new BusinessException(500, "Failed to delete document file");
        }
    }

    private void updateStatus(Long documentId, String status) {
        LearningDocument document = new LearningDocument();
        document.setId(documentId);
        document.setProcessStatus(status);
        documentMapper.updateById(document);
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t ]+", " ")
                .trim();
    }

    private String sanitizeFileName(String fileName) {
        String value = fileName == null || fileName.trim().isEmpty() ? "document.txt" : fileName.trim();
        value = Paths.get(value).getFileName().toString();
        value = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (value.length() > 120) {
            value = value.substring(value.length() - 120);
        }
        return value;
    }

    private String originalFileNameWithoutExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex <= 0 ? fileName : fileName.substring(0, dotIndex);
        return baseName.isEmpty() ? "document" : baseName;
    }

    private String fileType(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "txt";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private int normalizedChunkSize() {
        Integer configured = ragProperties.getChunkSize();
        if (configured == null || configured < 200) {
            return 800;
        }
        return Math.min(configured, 4000);
    }

    private int normalizedOverlap(int chunkSize) {
        Integer configured = ragProperties.getChunkOverlap();
        if (configured == null || configured < 0) {
            return 0;
        }
        return Math.min(configured, Math.max(chunkSize / 2, 1));
    }
}
