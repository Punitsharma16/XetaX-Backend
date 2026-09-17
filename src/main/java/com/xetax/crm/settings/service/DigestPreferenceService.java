package com.xetax.crm.settings.service;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.auth.user.AuthUserRepository;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.settings.entity.DigestPreference;
import com.xetax.crm.settings.repository.DigestPreferenceRepository;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The morning digest's channel settings.
 *
 * <p>The digest is addressed to the workspace owner, so only the owner can
 * change where it goes; a team member sees the settings but cannot save them.
 */
@Service
@RequiredArgsConstructor
public class DigestPreferenceService {

    private final DigestPreferenceRepository repository;
    private final CurrentUserProvider currentUserProvider;
    private final OrgSmtpService orgSmtpService;
    private final AuthUserRepository authUserRepository;
    private final WhatsAppMessagingService whatsAppMessagingService;

    /** What the digest is allowed to use. The bell is implied by {@code enabled}. */
    public record Channels(boolean enabled, boolean emailEnabled, boolean whatsappEnabled) {
        public static final Channels DEFAULTS = new Channels(true, true, true);
    }

    /**
     * Accounts opened on or after this date start with the WhatsApp digest off:
     * from 1 October 2026 Meta charges for every message a business sends,
     * replies inside the 24-hour window included. Older accounts keep what they
     * have always had, so nobody's digest disappears without them choosing it.
     */
    @Value("${app.digest-whatsapp-off-for-accounts-from:2026-09-18}")
    private String whatsappOffFrom;

    /** For the scheduler: what the owner saved, or the defaults for their account. */
    public Channels forOwner(String ownerUserId) {
        if (ownerUserId == null || ownerUserId.isBlank()) return Channels.DEFAULTS;
        return repository.findByOwnerUserId(ownerUserId)
                .map(p -> new Channels(p.isEnabled(), p.isEmailEnabled(), p.isWhatsappEnabled()))
                .orElseGet(() -> defaultsFor(ownerUserId));
    }

    /** No saved row: everything on, except WhatsApp for accounts opened after the cut-off. */
    Channels defaultsFor(String ownerUserId) {
        return new Channels(true, true, !isNewAccount(ownerUserId));
    }

    private boolean isNewAccount(String ownerUserId) {
        try {
            Instant cutoff = LocalDate.parse(whatsappOffFrom)
                    .atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
            return authUserRepository.findById(UUID.fromString(ownerUserId))
                    .map(user -> user.getCreateAt() != null && !user.getCreateAt().isBefore(cutoff))
                    .orElse(false);
        } catch (Exception e) {
            // Unknown account or unreadable date: keep the behaviour it always had.
            return false;
        }
    }

    public Map<String, Object> get() {
        String owner = ownerId();
        Channels channels = forOwner(owner);

        var user = authUserRepository.findById(UUID.fromString(owner)).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", channels.enabled());
        out.put("emailEnabled", channels.emailEnabled());
        out.put("whatsappEnabled", channels.whatsappEnabled());
        out.put("canEdit", isOwner());
        // What each channel still needs, so the panel can say why nothing arrives.
        out.put("emailReady", orgSmtpService.isConfiguredFor(owner));
        out.put("phoneOnProfile", user != null && user.getPhone() != null && !user.getPhone().isBlank());
        out.put("whatsappConnected", whatsAppMessagingService.connectedConfig(owner).isPresent());
        return out;
    }

    @Transactional
    public Map<String, Object> save(Boolean enabled, Boolean emailEnabled, Boolean whatsappEnabled) {
        if (!isOwner()) {
            throw new BadRequestException("Only the workspace owner can change the morning digest");
        }
        String owner = ownerId();
        // A first save starts from what the owner currently gets, not the entity defaults.
        DigestPreference preference = repository.findByOwnerUserId(owner).orElseGet(() -> {
            Channels current = forOwner(owner);
            return DigestPreference.builder().ownerUserId(owner)
                    .enabled(current.enabled())
                    .emailEnabled(current.emailEnabled())
                    .whatsappEnabled(current.whatsappEnabled())
                    .build();
        });
        if (enabled != null) preference.setEnabled(enabled);
        if (emailEnabled != null) preference.setEmailEnabled(emailEnabled);
        if (whatsappEnabled != null) preference.setWhatsappEnabled(whatsappEnabled);
        repository.save(preference);
        return get();
    }

    private String ownerId() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    private boolean isOwner() {
        UUID me = currentUserProvider.currentUserIdOrNull();
        UUID owner = currentUserProvider.currentDataOwnerIdOrNull();
        return me != null && me.equals(owner);
    }
}
