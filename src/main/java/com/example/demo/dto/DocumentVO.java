package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVO {
    private Long id;
    private String fileName;
    private long fileSize;
    private int chunkCount;
    private String status;
    private String errorMsg;
    private LocalDateTime createdAt;
}
