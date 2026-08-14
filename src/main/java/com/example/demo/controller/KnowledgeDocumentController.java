package com.example.demo.controller;

import com.example.demo.dto.DocumentVO;
import com.example.demo.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;

    /**
     * Upload a document into the knowledge base (parse -> split -> embed -> index).
     */
    @PostMapping("/upload")
    public ResponseEntity<DocumentVO> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(documentService.upload(file));
    }

    /**
     * List all knowledge-base documents.
     */
    @GetMapping
    public ResponseEntity<List<DocumentVO>> list() {
        return ResponseEntity.ok(documentService.list());
    }

    /**
     * Delete a document (ES vectors + disk file + registry row).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
