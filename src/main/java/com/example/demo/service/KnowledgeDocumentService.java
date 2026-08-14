package com.example.demo.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import com.example.demo.config.EsIndexInitializer;
import com.example.demo.dto.DocumentVO;
import com.example.demo.entity.KnowledgeDocument;
import com.example.demo.repository.KnowledgeDocumentRepository;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentLoader;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.source.FileSystemSource;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Document ingestion pipeline:
 * upload -> save to disk -> Tika parse -> split (char-based, 800/100)
 * -> BGE-M3 embed (batches of 32) -> index into ES -> registry row in MySQL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".pdf", ".docx", ".txt", ".md");
    private static final int SEGMENT_SIZE_CHARS = 800;
    private static final int SEGMENT_OVERLAP_CHARS = 100;
    private static final int EMBED_BATCH_SIZE = 32; // SiliconFlow limits a single request to <=64 inputs
    private static final int EMBED_MAX_ATTEMPTS = 3;

    private final KnowledgeDocumentRepository docRepo;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ElasticsearchClient esClient;
    private final EsIndexInitializer esIndexInitializer;

    @Value("${app.kb.upload-dir}")
    private String uploadDir;

    @Value("${app.kb.max-file-size-mb}")
    private int maxFileSizeMb;

    @Value("${app.elasticsearch.index-name}")
    private String indexName;

    public DocumentVO upload(MultipartFile file) {
        String rawName = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        // Path traversal defense: keep only the basename, strip any directory
        // components ("../", "\", absolute paths) from the client-supplied name.
        String fileName = Path.of(rawName).getFileName().toString();
        if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")) {
            fileName = "unnamed";
        }
        validate(fileName, file.getSize());

        String docId = UUID.randomUUID().toString().replace("-", "");
        KnowledgeDocument doc = KnowledgeDocument.builder()
                .docId(docId)
                .fileName(fileName)
                .fileSize(file.getSize())
                .status("PROCESSING")
                .build();
        docRepo.save(doc);

        try {
            // 1. Persist the raw file to disk
            Path dir = Path.of(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path stored = dir.resolve(docId + "_" + fileName).normalize();
            if (!stored.startsWith(dir)) {
                throw new IllegalArgumentException("非法文件名");
            }
            Files.write(stored, file.getBytes());
            doc.setStoredPath(stored.toString());

            // 2. Parse (PDF/DOCX/TXT via Apache Tika)
            Document document = DocumentLoader.load(new FileSystemSource(stored), new ApacheTikaDocumentParser());

            // 3. Split into chunks, tagging each with its source metadata
            List<TextSegment> segments = new ArrayList<>();
            int chunkIndex = 0;
            for (TextSegment seg : DocumentSplitters
                    .recursive(SEGMENT_SIZE_CHARS, SEGMENT_OVERLAP_CHARS)
                    .split(document)) {
                segments.add(TextSegment.from(seg.text(), new Metadata()
                        .put("docId", docId)
                        .put("fileName", fileName)
                        .put("chunkIndex", chunkIndex++)));
            }
            if (segments.isEmpty()) {
                throw new IllegalStateException("文档解析结果为空");
            }

            // 4. Embed in batches (BGE-M3, 1024-dim)
            List<Embedding> embeddings = new ArrayList<>();
            for (int start = 0; start < segments.size(); start += EMBED_BATCH_SIZE) {
                List<TextSegment> batch = segments.subList(
                        start, Math.min(start + EMBED_BATCH_SIZE, segments.size()));
                embeddings.addAll(embedWithRetry(batch));
            }

            // 5. Index into ES (creating the index lazily if the backend
            //    started before ES was available)
            esIndexInitializer.ensureIndex();
            List<String> ids = segments.stream()
                    .map(s -> docId + "_" + s.metadata().getInteger("chunkIndex"))
                    .toList();
            embeddingStore.addAll(ids, embeddings, segments);

            doc.setChunkCount(segments.size());
            doc.setStatus("READY");
            log.info("Document '{}' ingested: {} chunks indexed", fileName, segments.size());
        } catch (Exception e) {
            doc.setStatus("FAILED");
            doc.setErrorMsg(e.getMessage());
            // Clean up the partially persisted file so failed uploads don't
            // accumulate orphan files on disk.
            if (doc.getStoredPath() != null) {
                try {
                    Files.deleteIfExists(Path.of(doc.getStoredPath()));
                } catch (IOException cleanupError) {
                    log.warn("Failed to clean up file {}: {}", doc.getStoredPath(), cleanupError.getMessage());
                }
            }
            // Generic message only — internal details (ES/DB errors) stay in the log.
            log.error("Document '{}' ingestion failed", fileName, e);
            throw new IllegalStateException("文档处理失败，请检查文件格式是否正确后重试");
        } finally {
            docRepo.save(doc);
        }
        return toVO(doc);
    }

    public List<DocumentVO> list() {
        return docRepo.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toVO)
                .toList();
    }

    public void delete(Long id) {
        KnowledgeDocument doc = docRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + id));

        // 1. Remove vectors from ES by docId
        try {
            esClient.deleteByQuery(d -> d.index(indexName)
                    .query(q -> q.term(t -> t.field("metadata.docId")
                            .value(FieldValue.of(doc.getDocId())))));
        } catch (IOException e) {
            log.warn("Failed to delete vectors for doc {}: {}", id, e.getMessage());
        }

        // 2. Remove the raw file from disk — only if it resolves inside the
        //    upload directory (defense against tampered storedPath values).
        if (doc.getStoredPath() != null) {
            try {
                Path uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
                Path stored = Path.of(doc.getStoredPath()).toAbsolutePath().normalize();
                if (stored.startsWith(uploadRoot)) {
                    Files.deleteIfExists(stored);
                } else {
                    log.warn("Refusing to delete file outside upload dir: {}", doc.getStoredPath());
                }
            } catch (IOException e) {
                log.warn("Failed to delete file {}: {}", doc.getStoredPath(), e.getMessage());
            }
        }

        // 3. Remove the registry row
        docRepo.deleteById(id);
        log.info("Document '{}' (id={}) deleted", doc.getFileName(), id);
    }

    private List<Embedding> embedWithRetry(List<TextSegment> batch) {
        for (int attempt = 1; ; attempt++) {
            try {
                return embeddingModel.embedAll(batch).content();
            } catch (Exception e) {
                if (attempt >= EMBED_MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Embedding batch failed (attempt {}), retrying...", attempt);
            }
        }
    }

    private void validate(String fileName, long size) {
        if (size > (long) maxFileSizeMb * 1024 * 1024) {
            throw new IllegalArgumentException("文件超过 " + maxFileSizeMb + "MB 限制");
        }
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型: " + ext + "，仅支持 " + ALLOWED_EXTENSIONS);
        }
    }

    private DocumentVO toVO(KnowledgeDocument doc) {
        return DocumentVO.builder()
                .id(doc.getId())
                .fileName(doc.getFileName())
                .fileSize(doc.getFileSize())
                .chunkCount(doc.getChunkCount())
                .status(doc.getStatus())
                .errorMsg(doc.getErrorMsg())
                .createdAt(doc.getCreatedAt())
                .build();
    }
}
