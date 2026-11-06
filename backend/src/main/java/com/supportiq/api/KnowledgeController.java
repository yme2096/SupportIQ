package com.supportiq.api;

import com.supportiq.service.KnowledgeDocumentService;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge")
@PreAuthorize("hasRole('COMPANY_ADMIN')")
public class KnowledgeController {
    private final KnowledgeDocumentService documents;
    public KnowledgeController(KnowledgeDocumentService documents) { this.documents=documents; }

    @GetMapping public List<Map<String,Object>> list(@RequestParam(required=false) @Size(max=100) String search,
            @RequestParam(required=false) String status) { return documents.list(search,status); }
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> upload(@RequestParam("file") MultipartFile file) { return documents.upload(file); }
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable String id) { return documents.detail(id); }
    @GetMapping("/{id}/preview") public Map<String,Object> preview(@PathVariable String id) { return documents.preview(id); }
    @PostMapping("/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> retry(@PathVariable String id) { return documents.retry(id,false); }
    @PostMapping("/{id}/reprocess") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> reprocess(@PathVariable String id) { return documents.retry(id,true); }
    @DeleteMapping("/{id}") public Map<String,Object> delete(@PathVariable String id) { return documents.archive(id); }
}
