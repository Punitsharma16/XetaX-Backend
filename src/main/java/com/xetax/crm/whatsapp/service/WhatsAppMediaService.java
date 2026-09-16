package com.xetax.crm.whatsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.xetax.crm.whatsapp.client.MetaWhatsAppClient;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import com.xetax.crm.whatsapp.repository.WhatsAppConfigRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Inbound media — the part Meta does not give you.
 *
 * <p>A webhook for a photo carries only a media id. That id is useless to a
 * browser: the bytes sit behind the Graph API and need the workspace's access
 * token, and the download URL Meta hands back expires in minutes. So the file
 * is pulled once, the moment the message lands, stored on this server, and
 * given an unguessable key. That key is the shareable link — any browser can
 * open it, with no token and no login.
 *
 * <p>The download runs after the webhook transaction commits, on the WhatsApp
 * executor, so Meta's delivery is acknowledged immediately and a slow or large
 * file never makes the webhook time out (which would make Meta retry it).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppMediaService {

    private final MetaWhatsAppClient client;
    private final SecretEncryptionService encryption;
    private final WhatsAppMessageRepository messageRepository;
    private final WhatsAppConfigRepository configRepository;
    /** Self, through the proxy — an @Async call on `this` would run inline. */
    private final ObjectProvider<WhatsAppMediaService> self;

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${app.api-base-url:http://localhost:8085}")
    private String apiBaseUrl;

    @Value("${app.whatsapp-media-max-bytes:26214400}")
    private long maxBytes;

    /** One stored file, ready to be written to an HTTP response. */
    public record StoredMedia(byte[] bytes, String mimeType, String filename) {}

    /* ------------------------------------------------------------- fetching */

    /**
     * Queues the download for after the current transaction commits. Called
     * from the webhook, where the message row is not visible to another thread
     * until commit.
     */
    public void fetchAfterCommit(WhatsAppConfig config, Long messageId, String mediaId) {
        if (config == null || messageId == null || mediaId == null || mediaId.isBlank()) return;
        Long configId = config.getId();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            self.getObject().fetchAndStore(configId, messageId, mediaId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                self.getObject().fetchAndStore(configId, messageId, mediaId);
            }
        });
    }

    /**
     * Pulls the bytes from Meta and stores them. Never throws: a message whose
     * media could not be fetched still shows in the thread, just without a
     * link, and the media id stays on the row so it can be retried.
     */
    @Async("whatsappExecutor")
    @Transactional
    public void fetchAndStore(Long configId, Long messageId, String mediaId) {
        try {
            WhatsAppConfig config = configRepository.findById(configId).orElse(null);
            WhatsAppMessage message = messageRepository.findById(messageId).orElse(null);
            if (config == null || message == null) return;

            String token = encryption.decrypt(config.getAccessTokenEncrypted());
            if (token == null || token.isBlank()) {
                log.warn("No WhatsApp token for config {} — media {} not downloaded", configId, mediaId);
                return;
            }

            JsonNode meta = client.getMediaMetadata(mediaId, token);
            String url = meta.path("url").asText(null);
            String mime = meta.path("mime_type").asText("application/octet-stream");
            long declaredSize = meta.path("file_size").asLong(0);
            if (url == null || url.isBlank()) {
                log.warn("Meta returned no url for media {}", mediaId);
                return;
            }
            if (maxBytes > 0 && declaredSize > maxBytes) {
                log.warn("Inbound media {} is {} bytes — over the {} byte limit, not stored",
                        mediaId, declaredSize, maxBytes);
                return;
            }

            byte[] bytes = client.downloadMedia(url, token);
            if (bytes == null || bytes.length == 0) return;
            if (maxBytes > 0 && bytes.length > maxBytes) {
                log.warn("Inbound media {} downloaded {} bytes — over the limit, discarded",
                        mediaId, bytes.length);
                return;
            }

            String key = newKey();
            Path dir = Path.of(uploadDir, "whatsapp", config.getOwnerUserId());
            Files.createDirectories(dir);
            Path target = dir.resolve(key + extensionFor(mime, message.getMediaFilename()));
            Files.write(target, bytes);

            message.setMediaKey(key);
            message.setMediaMimeType(mime);
            message.setMediaSize((long) bytes.length);
            message.setMediaStoragePath(target.toAbsolutePath().toString());
            if (message.getMediaFilename() == null || message.getMediaFilename().isBlank()) {
                message.setMediaFilename(key + extensionFor(mime, null));
            }
            messageRepository.save(message);
            log.debug("Stored inbound WhatsApp media {} as {}", mediaId, key);
        } catch (Exception e) {
            log.warn("Inbound media {} could not be stored: {}", mediaId, e.getMessage());
        }
    }

    /* -------------------------------------------------------------- serving */

    /** The public, tokenless link for a stored file — null when there is none. */
    public String publicUrl(WhatsAppMessage message) {
        if (message == null || message.getMediaKey() == null || message.getMediaKey().isBlank()) return null;
        String base = apiBaseUrl == null ? "" : apiBaseUrl.trim().replaceAll("/+$", "");
        return base + "/api/public/whatsapp/media/" + message.getMediaKey();
    }

    /** Reads a stored file by the key from its public link. */
    public Optional<StoredMedia> load(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return messageRepository.findByMediaKey(key).flatMap(message -> {
            String path = message.getMediaStoragePath();
            if (path == null || path.isBlank()) return Optional.empty();
            try {
                byte[] bytes = Files.readAllBytes(Path.of(path));
                String mime = message.getMediaMimeType() == null
                        ? "application/octet-stream" : message.getMediaMimeType();
                String filename = message.getMediaFilename() == null
                        ? key : message.getMediaFilename();
                return Optional.of(new StoredMedia(bytes, mime, filename));
            } catch (Exception e) {
                log.warn("Stored media {} is missing on disk: {}", key, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /* -------------------------------------------------------------- helpers */

    /** 40 hex characters — long enough that the link cannot be guessed. */
    private static String newKey() {
        return (UUID.randomUUID().toString() + UUID.randomUUID())
                .replace("-", "").substring(0, 40);
    }

    private static String extensionFor(String mime, String filename) {
        if (filename != null && filename.contains(".")) {
            String fromName = filename.substring(filename.lastIndexOf('.')).toLowerCase();
            if (fromName.length() <= 6) return fromName;
        }
        String type = mime == null ? "" : mime.toLowerCase();
        int semicolon = type.indexOf(';');
        if (semicolon > 0) type = type.substring(0, semicolon).trim();
        return switch (type) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "video/mp4" -> ".mp4";
            case "video/3gpp" -> ".3gp";
            case "audio/mpeg" -> ".mp3";
            case "audio/ogg" -> ".ogg";
            case "audio/amr" -> ".amr";
            case "audio/aac" -> ".aac";
            case "application/pdf" -> ".pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
            case "application/msword" -> ".doc";
            case "application/vnd.ms-excel" -> ".xls";
            case "text/plain" -> ".txt";
            case "text/csv" -> ".csv";
            default -> "";
        };
    }
}
