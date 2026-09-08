package com.xetax.crm.whatsapp.service;

import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.whatsapp.dto.WhatsAppConversationResponse;
import com.xetax.crm.whatsapp.dto.WhatsAppMessageResponse;
import com.xetax.crm.whatsapp.entity.WhatsAppConversation;
import com.xetax.crm.whatsapp.repository.WhatsAppConversationRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WhatsAppConversationService {

    private final WhatsAppConversationRepository conversationRepository;
    private final WhatsAppMessageRepository messageRepository;
    private final WhatsAppConfigService configService;

    public Page<WhatsAppConversationResponse> list(int page, int size) {
        String owner = configService.currentUserId();
        return conversationRepository
                .findByOwnerUserIdOrderByLastMessageAtDesc(owner,
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)))
                .map(WhatsAppConversationService::toResponse);
    }

    /** Opening a thread returns its messages and clears the unread counter. */
    @Transactional
    public Page<WhatsAppMessageResponse> messages(Long conversationId, int page, int size) {
        String owner = configService.currentUserId();
        WhatsAppConversation conversation = conversationRepository
                .findByIdAndOwnerUserId(conversationId, owner)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        if (conversation.getUnreadCount() != 0) {
            conversation.setUnreadCount(0);
            conversationRepository.save(conversation);
        }
        return messageRepository
                .findByConversationIdAndOwnerUserIdOrderByIdDesc(conversationId, owner,
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)))
                .map(WhatsAppMessagingService::toResponse);
    }

    private static WhatsAppConversationResponse toResponse(WhatsAppConversation conversation) {
        boolean windowOpen = WhatsAppMessagingService
                .windowOpen(conversation.getLastInboundAt(), java.time.Instant.now());
        return WhatsAppConversationResponse.builder()
                .id(conversation.getId())
                .customerPhone(conversation.getCustomerPhone())
                .customerName(conversation.getCustomerName())
                .lastMessage(conversation.getLastMessage())
                .lastMessageAt(conversation.getLastMessageAt())
                .unreadCount(conversation.getUnreadCount())
                .status(conversation.getStatus())
                .recordId(conversation.getRecordId())
                .lastInboundAt(conversation.getLastInboundAt())
                .windowOpen(windowOpen)
                .windowExpiresAt(windowOpen
                        ? conversation.getLastInboundAt().plus(java.time.Duration.ofHours(24))
                        : null)
                .build();
    }
}
