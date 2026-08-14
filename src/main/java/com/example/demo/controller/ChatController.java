package com.example.demo.controller;

import com.example.demo.dto.ChatRequest;
import com.example.demo.service.ChatService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * Send a message and receive AI response via Server-Sent Events.
     */
    @PostMapping(value = "/stream")
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
        response.setContentType("text/event-stream;charset=UTF-8");
        // Log only the message length — message content may be sensitive.
        String message = request.getMessage();
        log.info("Chat request: sessionId={}, messageLength={}",
                request.getSessionId(), message == null ? 0 : message.length());
        return chatService.chat(request.getSessionId(), message);
    }

}
