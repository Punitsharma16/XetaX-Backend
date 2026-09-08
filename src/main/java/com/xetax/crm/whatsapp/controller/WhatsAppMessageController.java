package com.xetax.crm.whatsapp.controller;

import com.xetax.crm.team.service.RequiresPermission;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.whatsapp.dto.SendMessageRequest;
import com.xetax.crm.whatsapp.dto.WhatsAppConversationResponse;
import com.xetax.crm.whatsapp.dto.WhatsAppMessageResponse;
import com.xetax.crm.whatsapp.service.WhatsAppConversationService;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/whatsapp")
@RequiredArgsConstructor
public class WhatsAppMessageController {

    private final WhatsAppMessagingService messagingService;
    private final WhatsAppConversationService conversationService;

    /** One send: to a phone, a conversation, or a record's phone field. */
    @PostMapping("/messages/send")
    @RequiresPermission("whatsapp.inbox")
    public ApiResponse<WhatsAppMessageResponse> send(@RequestBody SendMessageRequest request) {
        return ResponseUtil.success("Message queued", messagingService.send(request));
    }

    /** Media send (multipart): file + phone ya conversationId + optional caption. */
    @PostMapping("/messages/send-media")
    @RequiresPermission("whatsapp.inbox")
    public ApiResponse<WhatsAppMessageResponse> sendMedia(
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) String caption,
            @RequestParam("file") MultipartFile file) {
        return ResponseUtil.success("Media queued",
                messagingService.sendMedia(phone, conversationId, file, caption));
    }

    @GetMapping("/messages/record/{recordId}")
    @RequiresPermission("whatsapp.inbox")
    public ApiResponse<List<WhatsAppMessageResponse>> recordHistory(@PathVariable String recordId) {
        return ResponseUtil.success("Record messages", messagingService.recordHistory(recordId));
    }

    @GetMapping("/conversations")
    @RequiresPermission("whatsapp.inbox")
    public ApiResponse<Page<WhatsAppConversationResponse>> conversations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseUtil.success("Conversations", conversationService.list(page, size));
    }

    @GetMapping("/conversations/{id}/messages")
    @RequiresPermission("whatsapp.inbox")
    public ApiResponse<Page<WhatsAppMessageResponse>> conversationMessages(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseUtil.success("Messages", conversationService.messages(id, page, size));
    }
}
