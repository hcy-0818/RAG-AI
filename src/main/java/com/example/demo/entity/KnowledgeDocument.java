package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Metadata of an uploaded knowledge-base document.
 * The file lives on local disk, its vectors live in Elasticsearch,
 * and this row is the registry tying them together.
 */
@Entity
@Table(name = "knowledge_document")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Ingest UUID, used as metadata.docId in ES for deletion by query */
    private String docId;

    /** Original uploaded file name */
    private String fileName;

    /** Path of the file on local disk */
    private String storedPath;

    /** File size in bytes */
    private long fileSize;

    /** Number of chunks after splitting */
    private int chunkCount;

    /** READY / FAILED */
    private String status;

    private String errorMsg;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
