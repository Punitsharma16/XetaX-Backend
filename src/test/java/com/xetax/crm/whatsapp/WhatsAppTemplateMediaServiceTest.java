package com.xetax.crm.whatsapp;

import com.xetax.crm.whatsapp.entity.WhatsAppTemplateMedia;
import com.xetax.crm.whatsapp.repository.WhatsAppTemplateMediaRepository;
import com.xetax.crm.whatsapp.service.WhatsAppTemplateMediaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A template's header file is hosted by us so every send has a public link.
 * The link is public, so what may be hosted is pinned too: only formats a
 * WhatsApp header can carry, never something a browser would run.
 */
class WhatsAppTemplateMediaServiceTest {

    @TempDir
    Path uploads;

    private final Map<String, WhatsAppTemplateMedia> byKey = new HashMap<>();
    private WhatsAppTemplateMediaService service;

    @BeforeEach
    void setUp() {
        WhatsAppTemplateMediaRepository repository = mock(WhatsAppTemplateMediaRepository.class);
        when(repository.save(any())).thenAnswer(inv -> {
            WhatsAppTemplateMedia media = inv.getArgument(0);
            byKey.put(media.getMediaKey(), media);
            return media;
        });
        when(repository.findByMediaKey(any()))
                .thenAnswer(inv -> Optional.ofNullable(byKey.get(inv.<String>getArgument(0))));
        service = new WhatsAppTemplateMediaService(repository);
        ReflectionTestUtils.setField(service, "uploadDir", uploads.toString());
        ReflectionTestUtils.setField(service, "apiBaseUrl", "https://api.xetacrm.pro/");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aPictureGetsAPublicLinkEndingInItsExtension() {
        String url = service.store("owner-1", bytes("jpeg"), "diwali.jpg", "image/jpeg").orElseThrow();
        assertTrue(url.matches("https://api\\.xetacrm\\.pro/api/public/whatsapp/template-media/[0-9a-f]{40}\\.jpg"), url);
    }

    @Test
    void theFileIsKeptUnderTheWorkspacesFolder() throws Exception {
        service.store("owner-1", bytes("png-bytes"), "offer.png", "image/png");
        WhatsAppTemplateMedia media = byKey.values().iterator().next();
        Path stored = Path.of(media.getStoragePath());
        assertTrue(stored.startsWith(uploads.resolve("whatsapp-templates").resolve("owner-1")), stored.toString());
        assertTrue(stored.toString().endsWith(".png"));
        assertEquals("png-bytes", Files.readString(stored));
        assertEquals("image/png", media.getMimeType());
        assertEquals("offer.png", media.getOriginalName());
        assertEquals(9L, media.getSize());
        assertEquals("owner-1", media.getOwnerUserId());
    }

    @Test
    void everyHeaderFormatIsHosted() {
        assertTrue(service.store("o", bytes("v"), "a.mp4", "video/mp4").orElseThrow().endsWith(".mp4"));
        assertTrue(service.store("o", bytes("v"), "a.3gp", "video/3gpp").orElseThrow().endsWith(".3gp"));
        assertTrue(service.store("o", bytes("d"), "a.pdf", "application/pdf").orElseThrow().endsWith(".pdf"));
    }

    @Test
    void looseContentTypesAreUnderstood() {
        assertTrue(service.store("o", bytes("x"), "a.jpg", "image/jpg").orElseThrow().endsWith(".jpg"));
        assertTrue(service.store("o", bytes("x"), "a.png", "IMAGE/PNG; charset=binary").orElseThrow().endsWith(".png"));
    }

    @Test
    void somethingABrowserWouldRunIsNeverHosted() {
        assertTrue(service.store("o", bytes("<script>alert(1)</script>"), "x.html", "text/html").isEmpty());
        assertTrue(service.store("o", bytes("<svg onload=alert(1)>"), "x.svg", "image/svg+xml").isEmpty());
        assertTrue(service.store("o", bytes("x"), "x.bin", null).isEmpty());
        assertTrue(byKey.isEmpty(), "nothing was saved");
    }

    @Test
    void anEmptyFileIsNotHosted() {
        assertTrue(service.store("o", new byte[0], "a.jpg", "image/jpeg").isEmpty());
    }

    @Test
    void everyUploadGetsItsOwnLink() {
        String first = service.store("o", bytes("a"), "a.jpg", "image/jpeg").orElseThrow();
        String second = service.store("o", bytes("a"), "a.jpg", "image/jpeg").orElseThrow();
        assertNotEquals(first, second);
    }

    @Test
    void theLinkReadsTheFileBack() {
        String url = service.store("o", bytes("jpeg"), "diwali.jpg", "image/jpeg").orElseThrow();
        String file = url.substring(url.lastIndexOf('/') + 1);

        WhatsAppTemplateMediaService.StoredFile stored = service.load(file).orElseThrow();
        assertArrayEquals(bytes("jpeg"), stored.bytes());
        assertEquals("image/jpeg", stored.mimeType());
        assertEquals("diwali.jpg", stored.filename());

        String key = file.substring(0, file.indexOf('.'));
        assertTrue(service.load(key).isPresent(), "the key works without its extension too");
    }

    @Test
    void anUnknownOrVanishedFileServesNothing() throws Exception {
        assertTrue(service.load("nope.jpg").isEmpty());
        assertTrue(service.load("").isEmpty());
        assertTrue(service.load(null).isEmpty());

        String url = service.store("o", bytes("x"), "a.jpg", "image/jpeg").orElseThrow();
        Files.delete(Path.of(byKey.values().iterator().next().getStoragePath()));
        assertTrue(service.load(url.substring(url.lastIndexOf('/') + 1)).isEmpty());
    }
}
