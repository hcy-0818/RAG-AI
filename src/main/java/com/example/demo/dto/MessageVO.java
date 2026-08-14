package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageVO {
    private Long id;
    private String role;
    private String content;
    private LocalDateTime createdAt;
    /** Knowledge-base citations backing this assistant message (nullable) */
    private List<CitationVO> citations;
}
