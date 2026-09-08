package com.xetax.crm.document;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @GetMapping
    @RequiresPermission("documents.view")
    public ApiResponse<List<DocumentFile>> list() {
        return ResponseUtil.success("Documents", documentService.list());
    }

    @PostMapping
    @RequiresPermission("documents.manage")
    public ApiResponse<DocumentFile> upload(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "name", required = false) String name)
            throws IOException {
        return ResponseUtil.success("Document uploaded", documentService.upload(file, name));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("documents.manage")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return ResponseUtil.success("Document deleted");
    }

    /** Plain download; add ?recordId= for a personalized copy (DOCX only). */
    @GetMapping("/{id}/download")
    @RequiresPermission("documents.view")
    public ResponseEntity<byte[]> download(@PathVariable Long id,
                                           @RequestParam(required = false) String recordId) {
        DocumentFile document = documentService.get(id);
        byte[] bytes = documentService.personalizedBytes(document, recordId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + document.getOriginalFilename() + "\"")
                .contentType(document.getContentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(document.getContentType()))
                .body(bytes);
    }

    @PostMapping("/{id}/send")
    @RequiresPermission("documents.view")
    public ApiResponse<Void> send(@PathVariable Long id,
                                  @RequestBody DocumentService.SendRequest request) {
        documentService.send(id, request);
        return ResponseUtil.success("Document sent");
    }

    @PostMapping("/{id}/send-bulk")
    @RequiresPermission("documents.view")
    public ApiResponse<Map<String, Object>> sendBulk(@PathVariable Long id,
                                                     @RequestBody DocumentService.BulkSendRequest request) {
        return ResponseUtil.success("Bulk send done", documentService.sendBulk(id, request));
    }
}
