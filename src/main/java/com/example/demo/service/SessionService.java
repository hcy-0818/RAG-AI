package com.example.demo.service;

import com.example.demo.dto.SessionVO;
import com.example.demo.entity.ChatSession;
import com.example.demo.repository.ChatMessageRepository;
import com.example.demo.repository.ChatSessionRepository;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatMemoryStore chatMemoryStore;

    /**
     * Get all sessions ordered by last update time (newest first).
     */
    public List<SessionVO> listSessions() {
        return sessionRepository.findAllByOrderByUpdatedAtDesc()
                .stream()
                .map(s -> SessionVO.builder()
                        .id(s.getId())
                        .title(s.getTitle())
                        .createdAt(s.getCreatedAt())
                        .updatedAt(s.getUpdatedAt())
                        .build())
                .toList();
    }

    /**
     * Create a new chat session.
     */
    @Transactional
    public SessionVO createSession() {
        ChatSession session = ChatSession.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .title("新对话")
                .build();
        session = sessionRepository.save(session);
        log.info("Created session: {}", session.getId());
        return SessionVO.builder()
                .id(session.getId())
                .title(session.getTitle())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    /**
     * Delete a session, its messages, and its Redis chat memory.
     */
    @Transactional
    public void deleteSession(String sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
        // Otherwise the memory lingers in Redis until its 7-day TTL.
        chatMemoryStore.deleteMessages(sessionId);
        log.info("Deleted session: {}", sessionId);
    }

    /**
     * Update session title based on first user message.
     */
    @Transactional
    public void updateTitle(String sessionId, String title) {
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setTitle(title.length() > 30 ? title.substring(0, 30) : title);
            sessionRepository.save(s);
        });
    }
}
