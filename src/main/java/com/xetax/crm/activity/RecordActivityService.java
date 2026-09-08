package com.xetax.crm.activity;

import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.auth.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Append-only history for records. {@link #log} swallows every failure —
 * the timeline is a byproduct, never a reason for a record operation to fail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecordActivityService {

    private final RecordActivityRepository repository;
    private final CurrentUserProvider currentUserProvider;

    /** ownerUserId is the record's form owner — the timeline's scoping wall. */
    public void log(String recordId, String ownerUserId, String type, String detail) {
        try {
            String actorId = null;
            String actorName = "System";
            AuthUserEntity actor = currentUserProvider.currentUserOrNull();
            if (actor != null) {
                actorId = String.valueOf(actor.getId());
                actorName = actor.getName() != null && !actor.getName().isBlank()
                        ? actor.getName() : actor.getEmail();
            }
            repository.save(RecordActivity.builder()
                    .ownerUserId(ownerUserId)
                    .recordId(recordId)
                    .actorId(actorId)
                    .actorName(actorName)
                    .type(type)
                    .detail(detail != null && detail.length() > 500 ? detail.substring(0, 500) : detail)
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("Activity log failed for record {}: {}", recordId, e.getMessage());
        }
    }

    public List<RecordActivity> listFor(String recordId, String ownerUserId) {
        return repository.findTop100ByRecordIdAndOwnerUserIdOrderByCreatedAtDesc(recordId, ownerUserId);
    }

    /** Record delete cleanup — its history goes with it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteFor(String recordId) {
        try {
            repository.deleteByRecordId(recordId);
        } catch (Exception e) {
            log.warn("Activity cleanup failed for record {}: {}", recordId, e.getMessage());
        }
    }
}
