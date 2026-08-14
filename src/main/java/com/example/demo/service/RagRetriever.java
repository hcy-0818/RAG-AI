package com.example.demo.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.demo.dto.CitationVO;
import com.example.demo.entity.KnowledgeDocument;
import com.example.demo.repository.KnowledgeDocumentRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval: kNN (BGE-M3 vectors) + BM25 (ES full-text on the same
 * index), fused with Reciprocal Rank Fusion, then assembled into a
 * system prompt with citation metadata.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetriever {

    private static final double KNN_MIN_SCORE = 0.35;
    /**
     * kNN relative floor: keep only chunks scoring >= top1 * this factor.
     * BGE-M3's absolute cosine baseline is too high for an absolute floor,
     * but within one query the gap is clean — measured "入职要准备什么":
     * relevant chunk 0.692 vs noise 0.395-0.453. Keeps citations focused.
     */
    private static final double KNN_RELATIVE_FLOOR = 0.8;
    /**
     * BM25 gate, normalized per query character. An absolute floor fails on
     * this corpus: with 5 docs indexed, a chatty 16-char essay request
     * scores 1.43 from single-char overlaps ("章", "文"), close to the 1.87
     * of the relevant query "年假". Per-char scores separate them cleanly —
     * measured 0.09 (irrelevant) vs 0.31-0.89 (relevant).
     */
    private static final double BM25_MIN_SCORE_PER_CHAR = 0.15;
    /**
     * BM25 relative floor: keep only hits scoring >= top1 * this factor.
     * Measured "入职要准备什么": relevant doc 2.43 vs noise 0.52-0.56
     * (single-word overlaps like "入职" in other docs).
     */
    private static final double BM25_RELATIVE_FLOOR = 0.3;
    /**
     * RRF route weights: BM25 (exact term overlap) is the strong signal,
     * kNN (semantic) only assists. Prevents BGE-M3's high cosine baseline
     * from pushing unrelated chunks into the citation list.
     */
    private static final double BM25_RRF_WEIGHT = 3.0;
    private static final double KNN_RRF_WEIGHT = 1.0;
    /**
     * Post-fusion relative floor: drop entries far below the fused top1.
     */
    private static final double FUSED_RELATIVE_FLOOR = 0.35;
    private static final int RRF_K = 60;
    private static final int SNIPPET_MAX_CHARS = 120;

    /** Base persona, always injected; RAG context is appended on top when retrieved. */
    static final String BASE_SYSTEM_PROMPT =
            "你是企业知识库问答助手。对于知识库中有的内容，基于资料准确回答并标注引用；" +
            "对于与知识库无关的问题（写作、闲聊、通用知识等），正常发挥你的能力回答。";

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ElasticsearchClient esClient;
    private final KnowledgeDocumentRepository docRepo;

    @Value("${app.elasticsearch.index-name}")
    private String indexName;

    public record RetrievalResult(List<CitationVO> citations, String systemPrompt) {
    }

    /**
     * Retrieve the top-k most relevant chunks for the query and build the
     * RAG system prompt. When nothing is retrieved, the system prompt is
     * still the base persona (no citations), so plain chat keeps the
     * knowledge-base assistant identity.
     *
     * BM25 term overlap is the gate: BGE-M3 cosine scores for short
     * Chinese queries sit on a high baseline (chatty and relevant queries
     * differ by ~0.02), so the vector route alone cannot separate them;
     * BM25 does (relevant queries hit, chatty ones don't).
     */
    public RetrievalResult retrieve(String query, int topK) {
        List<Content> bm25 = bm25Retrieve(query, topK * 2);
        if (bm25.isEmpty()) {
            log.debug("No BM25 hit for query, plain chat with base persona: {}", query);
            return new RetrievalResult(List.of(), BASE_SYSTEM_PROMPT);
        }
        List<Content> knn = knnRetrieve(query, topK * 2);

        // Drop chunks of documents whose ingestion failed: a FAILED upload may
        // have left partial vectors in ES that must not be retrieved.
        Set<String> readyDocIds = readyDocIds();
        knn = knn.stream().filter(c -> isReadyDoc(c, readyDocIds)).toList();
        bm25 = bm25.stream().filter(c -> isReadyDoc(c, readyDocIds)).toList();
        if (bm25.isEmpty()) {
            return new RetrievalResult(List.of(), BASE_SYSTEM_PROMPT);
        }

        // Weighted Reciprocal Rank Fusion: score = sum(weight / (k + rank))
        // across both routes, BM25 dominating. Implemented here (instead of
        // langchain4j's ReciprocalRankFuser) so the fused score is available
        // for the citations payload.
        Map<Content, Double> fusedScores = rrfScores(knn, bm25);
        List<Map.Entry<Content, Double>> top = fusedScores.entrySet().stream()
                .sorted(Map.Entry.<Content, Double>comparingByValue().reversed())
                .filter(e -> e.getValue() >= fusedTopScore(fusedScores) * FUSED_RELATIVE_FLOOR)
                .limit(topK)
                .toList();

        List<CitationVO> citations = new ArrayList<>();
        StringBuilder prompt = new StringBuilder(BASE_SYSTEM_PROMPT)
                .append("\n\n以下参考资料如与用户问题相关，请基于资料回答，")
                .append("并在回答末尾用 [n] 标注引用的资料编号；")
                .append("如资料与问题无关，忽略资料直接正常回答。")
                .append("用 **文字** 加粗强调重点；不要使用列表符号（-、*、1.）枚举，改用自然段落表述。\n\n参考资料:\n");
        int idx = 1;
        for (Map.Entry<Content, Double> entry : top) {
            Content c = entry.getKey();
            Metadata docMeta = c.textSegment().metadata();
            citations.add(CitationVO.builder()
                    .docName(docMeta.getString("fileName"))
                    .chunkIndex(chunkIndex(docMeta))
                    .snippet(snippet(c.textSegment().text()))
                    .score(entry.getValue())
                    .build());
            prompt.append("[").append(idx++).append("] ")
                    .append(c.textSegment().text()).append("\n");
        }
        return new RetrievalResult(citations, prompt.toString());
    }

    /**
     * Compute weighted RRF scores over both retrieval routes, deduplicating
     * by chunk text: the same chunk hit by both routes (or duplicate uploads
     * of the same content) keeps only its best rank within each route, and
     * its weighted scores accumulate across routes.
     */
    private Map<Content, Double> rrfScores(List<Content> knn, List<Content> bm25) {
        Map<String, Double> textScores = new LinkedHashMap<>();
        Map<String, Content> textToContent = new LinkedHashMap<>();
        addScores(textScores, textToContent, knn, KNN_RRF_WEIGHT);
        addScores(textScores, textToContent, bm25, BM25_RRF_WEIGHT);

        Map<Content, Double> scores = new LinkedHashMap<>();
        textScores.forEach((text, score) -> scores.put(textToContent.get(text), score));
        return scores;
    }

    private void addScores(Map<String, Double> textScores,
                           Map<String, Content> textToContent,
                           List<Content> list,
                           double weight) {
        Set<String> seen = new HashSet<>();
        for (int rank = 0; rank < list.size(); rank++) {
            Content c = list.get(rank);
            String text = c.textSegment().text();
            if (!seen.add(text)) {
                continue; // same text at a worse rank in this route: ignore
            }
            textScores.merge(text, weight / (RRF_K + rank + 1), Double::sum);
            textToContent.putIfAbsent(text, c);
        }
    }

    private double fusedTopScore(Map<Content, Double> fusedScores) {
        return fusedScores.values().stream().max(Double::compare).orElse(0.0);
    }

    /** docIds of documents whose ingestion completed successfully. */
    private Set<String> readyDocIds() {
        return docRepo.findAll().stream()
                .filter(d -> "READY".equals(d.getStatus()))
                .map(KnowledgeDocument::getDocId)
                .collect(Collectors.toSet());
    }

    private boolean isReadyDoc(Content c, Set<String> readyDocIds) {
        String docId = c.textSegment().metadata().getString("docId");
        return docId != null && readyDocIds.contains(docId);
    }

    /** Vector search via LangChain4j EmbeddingStore (kNN in ES). */
    private List<Content> knnRetrieve(String query, int maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(
                        EmbeddingSearchRequest.builder()
                                .queryEmbedding(queryEmbedding)
                                .maxResults(maxResults)
                                .minScore(KNN_MIN_SCORE)
                                .build())
                .matches();
        if (matches.isEmpty()) {
            return List.of();
        }
        // Relative cutoff: results are sorted by score, keep only those
        // within KNN_RELATIVE_FLOOR of the top hit.
        double topScore = matches.get(0).score();
        List<Content> contents = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : matches) {
            if (m.score() < topScore * KNN_RELATIVE_FLOOR) {
                break;
            }
            contents.add(Content.from(m.embedded()));
        }
        return contents;
    }

    /** BM25 full-text search on the same ES index, via the typed client. */
    private List<Content> bm25Retrieve(String query, int size) {
        try {
            SearchResponse<KbHit> response = esClient.search(s -> s
                            .index(indexName)
                            .query(q -> q.match(m -> m.field("text").query(query)))
                            .size(size),
                    KbHit.class);
            List<Hit<KbHit>> hits = response.hits().hits();
            if (hits.isEmpty()) {
                return List.of();
            }

            // Gate on the top hit's per-character score: chatty long queries
            // accumulate weak single-char overlaps, real term hits dominate.
            Double topScore = hits.get(0).score();
            if (topScore != null && topScore / query.length() < BM25_MIN_SCORE_PER_CHAR) {
                return List.of();
            }

            // Relative cutoff: drop hits far below the top one (weak
            // single-word overlaps in unrelated docs).
            double relativeFloor = (topScore == null ? 0 : topScore) * BM25_RELATIVE_FLOOR;
            List<Content> contents = new ArrayList<>();
            for (Hit<KbHit> hit : hits) {
                if (hit.score() != null && hit.score() < relativeFloor) {
                    continue;
                }
                KbHit src = hit.source();
                if (src == null) {
                    continue;
                }
                contents.add(Content.from(TextSegment.from(
                        src.getText(), Metadata.from(src.getMetadata()))));
            }
            return contents;
        } catch (IOException e) {
            log.warn("BM25 retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * DTO for deserializing ES _source. LangChain4j's internal Document
     * class is package-private, so BM25 hits are mapped with this.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KbHit {
        private float[] vector;
        private String text;
        private Map<String, Object> metadata;
    }

    /** flattened fields come back from ES as strings; kNN path keeps Integers. */
    private int chunkIndex(Metadata m) {
        Integer i = m.getInteger("chunkIndex");
        if (i != null) {
            return i;
        }
        String s = m.getString("chunkIndex");
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String snippet(String text) {
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() <= SNIPPET_MAX_CHARS
                ? clean
                : clean.substring(0, SNIPPET_MAX_CHARS) + "...";
    }
}
