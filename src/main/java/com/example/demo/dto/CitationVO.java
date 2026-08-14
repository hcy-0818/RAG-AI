package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A retrieved knowledge-base snippet shown as a citation under the AI answer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationVO {
    /** Source document name */
    private String docName;
    /** Chunk index inside the document */
    private int chunkIndex;
    /** First 120 chars of the chunk */
    private String snippet;
    /** RRF fusion score (rank-based, not similarity) */
    private double score;
}
