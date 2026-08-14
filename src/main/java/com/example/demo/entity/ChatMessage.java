package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_message")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 36, nullable = false)
    private String sessionId;

    @Column(length = 20, nullable = false)
    private String role; // "user" or "assistant"

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** JSON array of CitationVO for assistant messages backed by RAG retrieval */
    @Column(columnDefinition = "TEXT")
    private String citations;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
