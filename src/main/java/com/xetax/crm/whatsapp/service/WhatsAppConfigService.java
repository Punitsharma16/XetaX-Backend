package com.xetax.crm.whatsapp.service;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.common.exception.UnauthorizedException;
import com.xetax.crm.whatsapp.config.MetaWhatsAppProperties;
import com.xetax.crm.whatsapp.dto.WhatsAppConfigResponse;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.enums.WhatsAppConnectionStatus;
import com.xetax.crm.whatsapp.repository.WhatsAppConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-scoped access to the user's WhatsApp connection. All lookups go
 * through the current authenticated user — a config id or phone number id
 * coming from a client is never trusted on its own.
 */
@Service
@RequiredArgsConstructor
public class WhatsAppConfigService {

    private final WhatsAppConfigRepository configRepository;
    private final CurrentUserProvider currentUserProvider;
    private final MetaWhatsAppProperties properties;

    public String currentUserId() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) {
            throw new UnauthorizedException("Not authenticated");
        }
        return id.toString();
    }

    public Optional<WhatsAppConfig> myConfig() {
        return configRepository.findFirstByOwnerUserIdOrderByIdDesc(currentUserId());
    }

    /** The user's CONNECTED config, or a clean 404-style error. */
    public WhatsAppConfig requireConnectedConfig() {
        WhatsAppConfig config = myConfig().orElseThrow(
                () -> new ResourceNotFoundException("WhatsApp is not connected yet"));
        if (config.getStatus() != WhatsAppConnectionStatus.CONNECTED) {
            throw new ResourceNotFoundException("WhatsApp is not connected yet");
        }
        return config;
    }

    public WhatsAppConfigResponse statusResponse() {
        return myConfig().map(WhatsAppConfigService::toResponse)
                .orElse(WhatsAppConfigResponse.builder()
                        .status(WhatsAppConnectionStatus.DISCONNECTED.name())
                        .build());
    }

    @Transactional
    public WhatsAppConfigResponse disconnect() {
        WhatsAppConfig config = myConfig().orElseThrow(
                () -> new ResourceNotFoundException("WhatsApp is not connected yet"));
        config.setStatus(WhatsAppConnectionStatus.DISCONNECTED);
        config.setAccessTokenEncrypted(null);
        config.setLastError(null);
        configRepository.save(config);
        return toResponse(config);
    }

    /** Non-secret values the frontend needs to launch Embedded Signup. */
    public Map<String, Object> embeddedSignupMeta() {
        return Map.of(
                "appId", properties.getAppId() == null ? "" : properties.getAppId(),
                "configId", properties.getConfigId() == null ? "" : properties.getConfigId(),
                "graphApiVersion", properties.getGraphApiVersion(),
                "configured", properties.getAppId() != null && !properties.getAppId().isBlank()
        );
    }

    public static WhatsAppConfigResponse toResponse(WhatsAppConfig config) {
        return WhatsAppConfigResponse.builder()
                .id(config.getId())
                .status(config.getStatus() == null
                        ? WhatsAppConnectionStatus.DISCONNECTED.name() : config.getStatus().name())
                .wabaId(config.getWabaId())
                .phoneNumberId(config.getPhoneNumberId())
                .displayPhoneNumber(config.getDisplayPhoneNumber())
                .verifiedName(config.getVerifiedName())
                .qualityRating(config.getQualityRating())
                .accountMode(config.getAccountMode())
                .connectedAt(config.getConnectedAt())
                .lastSyncAt(config.getLastSyncAt())
                .lastError(config.getLastError())
                .webhookSubscribed(config.isWebhookSubscribed())
                .build();
    }
}
