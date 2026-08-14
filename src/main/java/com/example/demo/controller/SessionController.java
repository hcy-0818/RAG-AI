package com.example.demo.controller;

import com.example.demo.dto.CitationVO;
import com.example.demo.dto.MessageVO;
import com.example.demo.dto.SessionVO;
import com.example.demo.entity.ChatMessage;
import com.example.demo.service.ChatService;
import com.example.demo.service.SessionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    /**
     * List all sessions.
     */
    @GetMapping
    public ResponseEntity<List<SessionVO>> listSessions() {
        return ResponseEntity.ok(sessionService.listSessions());
    }

    /**
     * Create a new session.
     */
    @PostMapping
    public ResponseEntity<SessionVO> createSession() {
        return ResponseEntity.ok(sessionService.createSession());
    }

    /**
     * Delete a session.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable String id) {
        sessionService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get message history for a session.
     */
    @GetMapping("/{id}/messages")
    public ResponseEntity<List<MessageVO>> getMessages(@PathVariable String id) {
        List<ChatMessage> messages = chatService.getMessages(id);
        List<MessageVO> vos = messages.stream()
                .map(m -> MessageVO.builder()
                        .id(m.getId())
                        .role(m.getRole())
                        .content(m.getContent())
                        .createdAt(m.getCreatedAt())
                        .citations(parseCitations(m.getCitations()))
                        .build())
                .toList();
        return ResponseEntity.ok(vos);
    }

    private List<CitationVO> parseCitations(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<CitationVO>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse citations JSON: {}", e.getMessage());
            return null;
        }
    }
}
