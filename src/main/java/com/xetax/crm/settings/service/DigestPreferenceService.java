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

    /** For the scheduler: no saved row means everything on, as before. */
    public Channels forOwner(String ownerUserId) {
        if (ownerUserId == null || ownerUserId.isBlank()) return Channels.DEFAULTS;
        return repository.findByOwnerUserId(ownerUserId)
                .map(p -> new Channels(p.isEnabled(), p.isEmailEnabled(), p.isWhatsappEnabled()))
                .orElse(Channels.DEFAULTS);
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
        DigestPreference preference = repository.findByOwnerUserId(owner)
                .orElseGet(() -> DigestPreference.builder().ownerUserId(owner).build());
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
