package com.xetax.crm.auth.security;

import com.xetax.crm.auth.user.AuthUserEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Reads the authenticated user's id from the SecurityContext.
 *
 * <p>JwtAuthenticationFilter puts the full {@link AuthUserEntity} in the
 * context as the principal, so this is the ONLY trustworthy source of the
 * acting user's identity — never a header, request param or body field.
 */
@Component
public class CurrentUserProvider {

    public UUID currentUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUserEntity user) {
            return user.getId();
        }
        return null;
    }

    /**
     * The id that DATA is scoped by. For an organization owner this is their
     * own id; for a team member (parentId set) it is the owner's id — so the
     * whole team reads and writes the owner's forms/records/WhatsApp/meetings
     * without a single query changing. Solo users (parentId "#") behave
     * exactly as before, which is what keeps this refactor zero-risk.
     */
    public UUID currentDataOwnerIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUserEntity user) {
            String parentId = user.getParentId();
            if (parentId != null && !parentId.isBlank() && !"#".equals(parentId)) {
                try {
                    return UUID.fromString(parentId);
                } catch (IllegalArgumentException ignored) {
                    return user.getId();
                }
            }
            return user.getId();
        }
        return null;
    }

    /** The member entity itself (identity, role checks) — null when anonymous. */
    public AuthUserEntity currentUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUserEntity user) {
            return user;
        }
        return null;
    }
}
