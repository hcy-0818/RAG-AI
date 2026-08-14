package com.example.demo.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Ensures SSE responses carry charset=UTF-8.
 * SseEmitter sets Content-Type to "text/event-stream" without charset,
 * which can cause browsers to mis-decode Chinese characters.
 */
@Component
public class Utf8SseFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        chain.doFilter(request, new SseCharsetResponseWrapper((HttpServletResponse) response));
    }

    private static class SseCharsetResponseWrapper extends HttpServletResponseWrapper {

        SseCharsetResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setContentType(String type) {
            if (type != null && type.startsWith("text/event-stream") && !type.contains("charset")) {
                super.setContentType("text/event-stream;charset=UTF-8");
            } else {
                super.setContentType(type);
            }
        }
    }
}
