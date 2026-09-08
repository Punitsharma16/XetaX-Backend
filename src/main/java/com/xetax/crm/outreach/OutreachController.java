package com.xetax.crm.outreach;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.contact.EmailLog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Address-based communication endpoints shared by contact & record pages.
 * Permission checks live in the service (contacts.* OR records.* both pass).
 */
@RestController
@RequestMapping("/api/outreach")
@RequiredArgsConstructor
public class OutreachController {

    private final OutreachService outreachService;

    public record EmailSend(String to, String subject, String body) {}
    public record WhatsAppSend(String phone, String message, String templateName,
                               String templateLanguage, String recordId, String buttonsJson) {}

    @GetMapping("/emails")
    public ApiResponse<List<EmailLog>> emails(@RequestParam String to) {
        return ResponseUtil.success("Email history", outreachService.emailHistory(to));
    }

    @GetMapping("/whatsapp")
    public ApiResponse<List<Map<String, Object>>> whatsapp(@RequestParam String phone) {
        return ResponseUtil.success("WhatsApp history", outreachService.whatsappHistory(phone));
    }

    @PostMapping("/email")
    public ApiResponse<Void> sendEmail(@RequestBody EmailSend request) {
        outreachService.sendEmail(request.to(), request.subject(), request.body());
        return ResponseUtil.success("Email sent");
    }

    @PostMapping("/whatsapp")
    public ApiResponse<Void> sendWhatsApp(@RequestBody WhatsAppSend request) {
        outreachService.sendWhatsApp(request.phone(), request.message(),
                request.templateName(), request.templateLanguage(), request.recordId(),
                request.buttonsJson());
        return ResponseUtil.success("WhatsApp sent");
    }
}
