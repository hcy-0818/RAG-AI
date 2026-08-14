package com.example.demo.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed ChatMemoryStore.
 * Each session's chat memory is stored under key: chat:memory:{sessionId}
 * with a 7-day TTL.
 */
@Slf4j
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "chat:memory:";
    private static final long TTL_DAYS = 7;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = KEY_PREFIX + memoryId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<StoredMessage> stored = objectMapper.readValue(json,
                    new TypeReference<List<StoredMessage>>() {});
            return stored.stream()
                    .map(this::toChatMessage)
                    .toList();
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize chat memory for key: {}", key, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = KEY_PREFIX + memoryId;
        List<StoredMessage> stored = messages.stream()
                .map(this::toStoredMessage)
                .toList();
        try {
            String json = objectMapper.writeValueAsString(stored);
            redisTemplate.opsForValue().set(key, json, TTL_DAYS, TimeUnit.DAYS);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize chat memory for key: {}", key, e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = KEY_PREFIX + memoryId;
        redisTemplate.delete(key);
    }

    // --- Serialization helpers ---

    private ChatMessage toChatMessage(StoredMessage sm) {
        return switch (sm.type) {
            case "USER" -> UserMessage.from(sm.text);
            case "AI" -> AiMessage.from(sm.text);
            case "SYSTEM" -> SystemMessage.from(sm.text);
            default -> throw new IllegalStateException("Unknown message type: " + sm.type);
        };
    }

    private StoredMessage toStoredMessage(ChatMessage msg) {
        if (msg instanceof UserMessage um) {
            return new StoredMessage("USER", um.singleText());
        } else if (msg instanceof AiMessage am) {
            return new StoredMessage("AI", am.text());
        } else if (msg instanceof SystemMessage sm) {
            return new StoredMessage("SYSTEM", sm.text());
        }
        throw new IllegalStateException("Unknown ChatMessage type: " + msg.getClass());
    }

    /**
     * Simple DTO for JSON serialization of LangChain4j messages.
     */
    @SuppressWarnings("unused")
    private static class StoredMessage {
        public String type;
        public String text;

        StoredMessage() {}

        StoredMessage(String type, String text) {
            this.type = type;
            this.text = text;
        }
    }
}
