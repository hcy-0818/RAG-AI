package com.example.demo.service;

import com.example.demo.entity.ChatMessage;
import com.example.demo.repository.ChatMessageRepository;
import com.example.demo.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final OpenAiStreamingChatModel streamingChatModel;
    private final ChatMemoryProvider chatMemoryProvider;
    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final SessionService sessionService;
    private final RagRetriever ragRetriever;
    private final ObjectMapper objectMapper;

    /** Messages to send to the model plus the citations JSON (null if no RAG hit). */
    private record RagContext(List<dev.langchain4j.data.message.ChatMessage> messages,
                              String citationsJson) {
    }

    /**
     * Send a message and stream the AI response via SSE.
     *
     * Memory isolation: each sessionId gets its own ChatMemory,
     * backed by Redis (via ChatMemoryStore), so different sessions
     * have completely separate conversation contexts.
     *
     * RAG: before streaming, the message is used to retrieve relevant
     * knowledge-base chunks (hybrid kNN+BM25+RRF). On a hit, a
     * "citations" SSE event is sent first and the retrieved context is
     * prepended as a SystemMessage for this call only (not persisted
     * to memory).
     */
    public SseEmitter chat(String sessionId, String userMessage) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout

        // 1. Save user message to MySQL
        saveMessage(sessionId, "user", userMessage);

        // 2. Get session-scoped memory (one singleton per session, see AiConfig)
        ChatMemory memory = chatMemoryProvider.get(sessionId);

        // 3. Under the per-session lock: restore history on Redis miss, set
        //    the title on the first message, and append the user message.
        //    The lock serializes concurrent requests for the same session so
        //    the Redis read-modify-write cannot lose turns.
        List<dev.langchain4j.data.message.ChatMessage> baseMessages;
        synchronized (memory) {
            if (memory.messages().isEmpty()) {
                restoreMemoryFromDatabaseLocked(sessionId, memory);
            }
            updateSessionTitleIfFirst(sessionId, userMessage);
            memory.add(UserMessage.from(userMessage));
            baseMessages = new ArrayList<>(memory.messages());
        }

        // 4. RAG retrieval (synchronous, before streaming starts)
        RagContext rag = retrieveRagContext(emitter, userMessage, baseMessages);

        // 6. Stream AI response
        StringBuilder fullResponse = new StringBuilder();

        streamingChatModel.chat(
                rag.messages(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        fullResponse.append(partialResponse);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("token")
                                    .data(partialResponse));
                        } catch (IOException e) {
                            log.error("SSE send error", e);
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        String aiContent = fullResponse.toString();
                        // Save AI response to memory (persisted to Redis)
                        synchronized (memory) {
                            memory.add(AiMessage.from(aiContent));
                        }
                        // Save AI response to MySQL, with citations if retrieved
                        saveMessage(sessionId, "assistant", aiContent, rag.citationsJson());
                        // Send completion event
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("done")
                                    .data("[DONE]"));
                            emitter.complete();
                        } catch (IOException e) {
                            log.error("SSE complete error", e);
                            emitter.completeWithError(e);
                        }
                        log.info("Chat complete for session {}, response length: {}",
                                sessionId, aiContent.length());
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("Chat streaming error for session {}", sessionId, error);
                        // Persist a placeholder assistant message so the user
                        // message saved earlier doesn't stay orphaned in
                        // MySQL/memory after a page reload.
                        String errText = "AI 回复出错: " + error.getMessage();
                        try {
                            synchronized (memory) {
                                memory.add(AiMessage.from(errText));
                            }
                            saveMessage(sessionId, "assistant", errText);
                        } catch (Exception persistError) {
                            log.warn("Failed to persist error message: {}", persistError.getMessage());
                        }
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data(errText));
                            emitter.complete();
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    }
                }
        );

        return emitter;
    }

    /**
     * Retrieve knowledge-base chunks for the user message. Sends the
     * citations SSE event on a hit. The persona system message is always
     * injected (base persona when nothing is retrieved, persona + context
     * on a hit); falls back to the base messages if retrieval itself
     * throws.
     */
    private RagContext retrieveRagContext(SseEmitter emitter, String userMessage,
                                          List<dev.langchain4j.data.message.ChatMessage> baseMessages) {
        try {
            RagRetriever.RetrievalResult rag = ragRetriever.retrieve(userMessage, 5);
            String citationsJson = null;
            if (!rag.citations().isEmpty()) {
                citationsJson = objectMapper.writeValueAsString(rag.citations());
                emitter.send(SseEmitter.event().name("citations").data(citationsJson));
            }

            List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(rag.systemPrompt()));
            messages.addAll(baseMessages);
            return new RagContext(messages, citationsJson);
        } catch (Exception e) {
            log.warn("RAG retrieval failed, falling back to plain chat: {}", e.getMessage());
            return new RagContext(baseMessages, null);
        }
    }

    /**
     * Restore memory from MySQL history when Redis is empty (restart or TTL
     * expiry). Caller must hold the per-session memory lock. Each add()
     * persists to Redis, so after this call the memory is re-cached.
     */
    private void restoreMemoryFromDatabaseLocked(String sessionId, ChatMemory memory) {
        List<ChatMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        for (ChatMessage msg : history) {
            if ("user".equals(msg.getRole())) {
                memory.add(UserMessage.from(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                memory.add(AiMessage.from(msg.getContent()));
            }
        }
        if (!history.isEmpty()) {
            log.info("Restored {} messages from DB for session {}", history.size(), sessionId);
        }
    }

    /**
     * Get message history for a session from MySQL.
     */
    public List<ChatMessage> getMessages(String sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Transactional
    void saveMessage(String sessionId, String role, String content) {
        saveMessage(sessionId, role, content, null);
    }

    @Transactional
    void saveMessage(String sessionId, String role, String content, String citations) {
        ChatMessage msg = ChatMessage.builder()
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .citations(citations)
                .build();
        messageRepository.save(msg);
    }

    /**
     * Set the session title from the first user message. Guarded by the
     * still-default title check: concurrent first messages are serialized
     * on the per-session memory lock, so only one of them wins and no
     * full-history count query is needed.
     */
    private void updateSessionTitleIfFirst(String sessionId, String userMessage) {
        sessionRepository.findById(sessionId).ifPresent(s -> {
            if ("新对话".equals(s.getTitle())) {
                s.setTitle(userMessage.length() > 30 ? userMessage.substring(0, 30) : userMessage);
                sessionRepository.save(s);
            }
        });
    }
}
