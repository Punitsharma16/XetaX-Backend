package com.xetax.crm.whatsapp.service;

import com.xetax.crm.whatsapp.entity.WhatsAppTemplateMedia;
import com.xetax.crm.whatsapp.repository.WhatsAppTemplateMediaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hosts template header files so a send always has a public link to carry.
 *
 * <p>Files land in {@code {app.upload-dir}/whatsapp-templates/{owner}/} and are
 * served, without login, from
 * {@code {app.api-base-url}/api/public/whatsapp/template-media/{key}.{ext}}.
 * Only the formats a WhatsApp header can carry are hosted: anything else
 * would turn a public link on our API domain into a place to serve arbitrary
 * content from.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppTemplateMediaService {

    /** The formats Meta accepts in a template header, with the extension each is served under. */
    static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "video/mp4", ".mp4",
            "video/3gpp", ".3gp",
            "application/pdf", ".pdf");

    private final WhatsAppTemplateMediaRepository repository;

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${app.api-base-url:http://localhost:8085}")
    private String apiBaseUrl;

    public record StoredFile(byte[] bytes, String mimeType, String filename) {}

    /**
     * Keeps the file and returns its public link — empty when the format is not
     * one a header can carry, so the owner can still paste a link of their own.
     */
    public Optional<String> store(String ownerUserId, byte[] bytes, String originalName, String contentType) {
        String mime = normalise(contentType);
        String extension = EXTENSIONS.get(mime);
        if (extension == null || bytes == null || bytes.length == 0) return Optional.empty();
        try {
            String key = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 40);
            Path dir = Path.of(uploadDir, "whatsapp-templates", ownerUserId);
            Files.createDirectories(dir);
            Path target = dir.resolve(key + extension);
            Files.write(target, bytes);
            repository.save(WhatsAppTemplateMedia.builder()
                    .ownerUserId(ownerUserId)
                    .mediaKey(key)
                    .storagePath(target.toAbsolutePath().toString())
                    .mimeType(mime)
                    .originalName(originalName == null ? null
                            : originalName.length() > 255 ? originalName.substring(0, 255) : originalName)
                    .size((long) bytes.length)
                    .build());
            return Optional.of(publicUrl(key, extension));
        } catch (Exception e) {
            // The sample still reached Meta; the owner can paste a link instead.
            log.warn("Template media could not be stored: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Reads a file by the last segment of its link — the key, with or without its extension. */
    public Optional<StoredFile> load(String file) {
        if (file == null || file.isBlank()) return Optional.empty();
        String key = file.contains(".") ? file.substring(0, file.indexOf('.')) : file;
        return repository.findByMediaKey(key).flatMap(media -> {
            try {
                byte[] bytes = Files.readAllBytes(Path.of(media.getStoragePath()));
                String name = media.getOriginalName() == null || media.getOriginalName().isBlank()
                        ? key + EXTENSIONS.getOrDefault(media.getMimeType(), "")
                        : media.getOriginalName();
                return Optional.of(new StoredFile(bytes, media.getMimeType(), name));
            } catch (Exception e) {
                log.warn("Template media {} is missing on disk: {}", key, e.getMessage());
                return Optional.empty();
            }
        });
    }

    String publicUrl(String key, String extension) {
        String base = apiBaseUrl == null ? "" : apiBaseUrl.trim().replaceAll("/+$", "");
        return base + "/api/public/whatsapp/template-media/" + key + extension;
    }

    private static String normalise(String contentType) {
        if (contentType == null) return "";
        String type = contentType.toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        if (semicolon >= 0) type = type.substring(0, semicolon);
        type = type.trim();
        return "image/jpg".equals(type) ? "image/jpeg" : type;
    }
}
