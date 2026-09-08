package com.xetax.crm.contact;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    public record SendEmailRequest(String subject, String body) {}
    public record SendWhatsAppRequest(String message) {}
    public record BulkEmailRequest(List<Long> ids, String subject, String body) {}
    public record BulkWhatsAppRequest(List<Long> ids, String message, String templateName, String templateLanguage) {}

    @PostMapping("/import")
    @RequiresPermission("contacts.manage")
    public ApiResponse<ContactService.ImportResult> importCsv(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ResponseUtil.success("Import finished", contactService.importCsv(file));
    }

    @GetMapping("/export")
    @RequiresPermission("contacts.view")
    public org.springframework.http.ResponseEntity<byte[]> exportCsv() {
        byte[] bytes = contactService.exportCsv().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=contacts.csv")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(bytes);
    }

    @GetMapping("/duplicates")
    @RequiresPermission("contacts.view")
    public ApiResponse<List<Map<String, Object>>> duplicates(
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) Long excludeId) {
        return ResponseUtil.success("Duplicates", contactService.duplicates(phone, email, excludeId));
    }

    @GetMapping("/{id:\\d+}")
    @RequiresPermission("contacts.view")
    public ApiResponse<Contact> get(@PathVariable Long id) {
        return ResponseUtil.success("Contact", contactService.get(id));
    }

    @GetMapping
    @RequiresPermission("contacts.view")
    public ApiResponse<Page<Contact>> list(@RequestParam(required = false) String query,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        return ResponseUtil.success("Contacts", contactService.list(query, page, size));
    }

    @PostMapping
    @RequiresPermission("contacts.manage")
    public ApiResponse<Contact> create(@RequestBody Contact contact) {
        return ResponseUtil.success("Contact created", contactService.create(contact));
    }

    @PutMapping("/{id}")
    @RequiresPermission("contacts.manage")
    public ApiResponse<Contact> update(@PathVariable Long id, @RequestBody Contact contact) {
        return ResponseUtil.success("Contact updated", contactService.update(id, contact));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("contacts.manage")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        contactService.delete(id);
        return ResponseUtil.success("Contact deleted");
    }

    @GetMapping("/{id}/emails")
    @RequiresPermission("contacts.view")
    public ApiResponse<List<EmailLog>> emailHistory(@PathVariable Long id) {
        return ResponseUtil.success("Email history", contactService.emailHistory(id));
    }

    @PostMapping("/{id}/email")
    @RequiresPermission("contacts.manage")
    public ApiResponse<Void> sendEmail(@PathVariable Long id, @RequestBody SendEmailRequest request) {
        contactService.sendEmail(id, request.subject(), request.body());
        return ResponseUtil.success("Email sent");
    }

    @PostMapping("/{id}/whatsapp")
    @RequiresPermission("contacts.manage")
    public ApiResponse<Void> sendWhatsApp(@PathVariable Long id, @RequestBody SendWhatsAppRequest request) {
        contactService.sendWhatsApp(id, request.message());
        return ResponseUtil.success("WhatsApp sent");
    }

    @PostMapping("/bulk-email")
    @RequiresPermission("contacts.manage")
    public ApiResponse<Map<String, Object>> bulkEmail(@RequestBody BulkEmailRequest request) {
        return ResponseUtil.success("Bulk email done",
                contactService.bulkEmail(request.ids(), request.subject(), request.body()));
    }

    @PostMapping("/bulk-whatsapp")
    @RequiresPermission("contacts.manage")
    public ApiResponse<Map<String, Object>> bulkWhatsApp(@RequestBody BulkWhatsAppRequest request) {
        return ResponseUtil.success("Bulk WhatsApp done",
                contactService.bulkWhatsApp(request.ids(), request.message(),
                        request.templateName(), request.templateLanguage()));
    }
}
