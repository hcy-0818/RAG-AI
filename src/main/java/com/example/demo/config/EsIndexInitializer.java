package com.example.demo.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * LangChain4j 1.0.0-beta2's ElasticsearchEmbeddingStore does not create the
 * index automatically, so we create it on startup with an explicit mapping:
 * - vector: 1024-dim dense_vector (cosine), matching BGE-M3 output
 * - text: analyzed with the built-in cjk analyzer (Chinese bigram) for BM25
 * - metadata: flattened, supports term filters (e.g. delete by docId)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsIndexInitializer implements ApplicationRunner {

    private static final int BGE_M3_DIMENSION = 1024;

    private final ElasticsearchClient esClient;

    @Value("${app.elasticsearch.index-name}")
    private String indexName;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            ensureIndex();
        } catch (Exception e) {
            // Keep the app usable (plain chat) when ES is down; uploads will
            // retry via ensureIndex() lazily.
            log.warn("Elasticsearch unavailable at startup, index '{}' not created: {}",
                    indexName, e.getMessage());
        }
    }

    /**
     * Idempotently create the kb_vectors index. Called on startup and lazily
     * from the ingestion pipeline, so a backend started before ES (or an ES
     * restart) doesn't require an application restart.
     */
    public void ensureIndex() throws IOException {
        if (esClient.indices().exists(e -> e.index(indexName)).value()) {
            return;
        }
        esClient.indices().create(c -> c.index(indexName).mappings(m -> m
                .properties("vector", p -> p.denseVector(d -> d
                        .dims(BGE_M3_DIMENSION)
                        .index(true)
                        .similarity("cosine")))
                .properties("text", p -> p.text(t -> t.analyzer("cjk")))
                .properties("metadata", p -> p.flattened(f -> f))));
        log.info("Created ES index '{}' (1024-dim dense_vector + cjk text + flattened metadata)",
                indexName);
    }
}
