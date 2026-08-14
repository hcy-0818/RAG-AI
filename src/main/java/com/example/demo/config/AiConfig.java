package com.example.demo.config;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class AiConfig {

    @Value("${langchain4j.open-ai.streaming-chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.streaming-chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.streaming-chat-model.model-name}")
    private String modelName;

    @Value("${langchain4j.open-ai.streaming-chat-model.max-tokens}")
    private int maxTokens;

    @Value("${langchain4j.open-ai.streaming-chat-model.temperature}")
    private double temperature;

    /**
     * Redis-backed persistent ChatMemoryStore.
     */
    @Bean
    ChatMemoryStore chatMemoryStore(StringRedisTemplate stringRedisTemplate) {
        return new RedisChatMemoryStore(stringRedisTemplate);
    }

    /**
     * Provides an isolated ChatMemory per session.
     * The memoryId is the sessionId, ensuring each session has independent memory.
     *
     * One singleton ChatMemory per session: creating a fresh instance per
     * request caused lost updates when two requests for the same session
     * read-modify-write Redis concurrently. Callers must synchronize on the
     * returned instance when mutating it.
     */
    @Bean
    ChatMemoryProvider chatMemoryProvider(ChatMemoryStore chatMemoryStore) {
        Map<String, ChatMemory> cache = new ConcurrentHashMap<>();
        return memoryId -> cache.computeIfAbsent(String.valueOf(memoryId), id ->
                MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(20) // keep last 20 messages in memory
                        .chatMemoryStore(chatMemoryStore)
                        .build());
    }

    /**
     * Streaming chat model for DeepSeek (OpenAI-compatible).
     */
    @Bean
    OpenAiStreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * Embedding model for RAG: SiliconFlow BGE-M3 (OpenAI-compatible, 1024-dim).
     */
    @Bean
    OpenAiEmbeddingModel embeddingModel(
            @Value("${langchain4j.open-ai.embedding-model.base-url}") String embeddingBaseUrl,
            @Value("${langchain4j.open-ai.embedding-model.api-key}") String embeddingApiKey,
            @Value("${langchain4j.open-ai.embedding-model.model-name}") String embeddingModelName) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embeddingBaseUrl)
                .apiKey(embeddingApiKey)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}
