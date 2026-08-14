package com.example.demo.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationKnn;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch beans: low-level RestClient, typed ElasticsearchClient
 * (used for BM25 queries and index management), and the LangChain4j
 * EmbeddingStore backed by ES kNN search.
 */
@Configuration
public class EsConfig {

    @Bean
    RestClient elasticsearchRestClient(@Value("${app.elasticsearch.host}") String host,
                                       @Value("${app.elasticsearch.port}") int port) {
        return RestClient.builder(new HttpHost(host, port, "http")).build();
    }

    @Bean
    ElasticsearchClient elasticsearchClient(RestClient restClient) {
        return new ElasticsearchClient(
                new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }

    @Bean
    EmbeddingStore<TextSegment> embeddingStore(RestClient restClient,
                                               @Value("${app.elasticsearch.index-name}") String indexName) {
        return ElasticsearchEmbeddingStore.builder()
                .restClient(restClient)
                .indexName(indexName)
                .configuration(ElasticsearchConfigurationKnn.builder()
                        .numCandidates(200)
                        .build())
                .build();
    }
}
