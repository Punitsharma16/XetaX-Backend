package com.xetax.crm.notification;

import com.xetax.crm.auth.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository repository;
    private final CurrentUserProvider currentUserProvider;

    /** Fire-and-forget — a failed notification must never break the caller. */
    public void push(String ownerUserId, String targetUserId, String type,
                     String title, String body, String link) {
        try {
            repository.save(Notification.builder()
                    .ownerUserId(ownerUserId)
                    .targetUserId(targetUserId)
                    .type(type)
                    .title(cut(title, 200))
                    .body(cut(body, 1000))
                    .link(cut(link, 300))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("Notification push failed: {}", e.getMessage());
        }
    }

    private String cut(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    private String me() {
        UUID id = currentUserProvider.currentUserIdOrNull();
        return id == null ? "" : id.toString();
    }

    public Map<String, Object> myFeed() {
        String me = me();
        List<Notification> items = repository.findTop30ByTargetUserIdOrderByIdDesc(me);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unread", repository.countByTargetUserIdAndReadAtIsNull(me));
        out.put("items", items);
        return out;
    }

    public void markRead(Long id) {
        repository.findByIdAndTargetUserId(id, me()).ifPresent(n -> {
            if (n.getReadAt() == null) {
                n.setReadAt(LocalDateTime.now());
                repository.save(n);
            }
        });
    }

    @Transactional
    public void markAllRead() {
        repository.markAllRead(me(), LocalDateTime.now());
    }
}
