package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank(message = "sessionId 不能为空")
    private String sessionId;

    @NotBlank(message = "消息不能为空")
    @Size(max = 4000, message = "消息过长（最多 4000 字符）")
    private String message;
}
