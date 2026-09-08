package com.xetax.crm.data_manager.service;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.repository.FormRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The single ownership gate for everything that hangs off a form.
 *
 * <p>Every CRM entity (field, stage, record, automation, integration) belongs
 * to a form, and a form belongs to the user whose UUID is stamped in
 * {@code ownerUserId}. Services call this guard after resolving their entity,
 * so the SAME rule protects the REST API and the AI tools alike.
 *
 * <p>A form that belongs to someone else fails with the same
 * ResourceNotFoundException the services already throw for a missing id —
 * callers cannot tell "not yours" from "does not exist", and existing API
 * behavior/messages stay unchanged for the owner.
 *
 * <p>When there is no authenticated user the check is skipped: the only
 * unauthenticated path into these services is the public webhook ingest
 * (PublicIntegrationController), which is already authorised by its
 * integration key + X-API-KEY pair.
 */
@Component
@RequiredArgsConstructor
public class OwnershipGuard {

    private final CurrentUserProvider currentUserProvider;

    private final FormRepo formRepo;

    private final FormOwnerCache formOwnerCache;

    /** Throws not-found when the form is not owned by the current user. */
    public void assertOwned(FormEntity form) {
        // Warm the owner cache — the entity is loaded anyway on this path.
        formOwnerCache.putOwner(form.getId(), form.getOwnerUserId());
        UUID userId = currentUserProvider.currentDataOwnerIdOrNull();
        if (userId == null) {
            return; // public ingest — authorised by the integration's API key
        }
        if (form.getOwnerUserId() == null || !form.getOwnerUserId().equals(userId.toString())) {
            throw new ResourceNotFoundException("Form Not Found");
        }
    }

    /**
     * Asserts ownership of a form by id.
     *
     * <p>Fast path: the Redis owner cache answers without touching MySQL —
     * this runs on every field/stage/record request, so hot forms cost zero
     * form-table queries. Cache miss (or Redis down) falls back to the DB.
     */
    public FormEntity requireOwnedForm(Long formId) {
        UUID userId = currentUserProvider.currentDataOwnerIdOrNull();
        if (userId != null) {
            var cachedOwner = formOwnerCache.getOwner(formId);
            if (cachedOwner.isPresent()) {
                if (!cachedOwner.get().equals(userId.toString())) {
                    throw new ResourceNotFoundException("Form Not Found");
                }
                return null; // authorised from cache — callers ignore the entity
            }
        }
        FormEntity form = formRepo.findById(formId)
                .orElseThrow(() -> new ResourceNotFoundException("Form Not Found"));
        assertOwned(form);
        return form;
    }

    /** True when the current user owns the form — for filtering lists. */
    public boolean owns(FormEntity form) {
        UUID userId = currentUserProvider.currentDataOwnerIdOrNull();
        return userId != null
                && form.getOwnerUserId() != null
                && form.getOwnerUserId().equals(userId.toString());
    }
}
