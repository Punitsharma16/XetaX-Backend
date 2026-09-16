package com.xetax.crm.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.whatsapp.client.MetaWhatsAppClient;
import com.xetax.crm.whatsapp.entity.WhatsAppConfig;
import com.xetax.crm.whatsapp.entity.WhatsAppMessage;
import com.xetax.crm.whatsapp.repository.WhatsAppConfigRepository;
import com.xetax.crm.whatsapp.repository.WhatsAppMessageRepository;
import com.xetax.crm.whatsapp.service.SecretEncryptionService;
import com.xetax.crm.whatsapp.service.WhatsAppMediaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Turning a Meta media id into a file anyone can open: pull it once, store it
 * under the workspace's folder, and mint the unguessable key that the public
 * link is made of.
 */
class WhatsAppMediaServiceTest {

    @TempDir
    Path uploads;

    private MetaWhatsAppClient client;
    private WhatsAppMessageRepository messageRepository;
    private WhatsAppConfigRepository configRepository;
    private WhatsAppMediaService service;

    @BeforeEach
    void setUp() {
        client = mock(MetaWhatsAppClient.class);
        messageRepository = mock(WhatsAppMessageRepository.class);
        configRepository = mock(WhatsAppConfigRepository.class);
        SecretEncryptionService encryption = mock(SecretEncryptionService.class);
        when(encryption.decrypt(any())).thenReturn("TOKEN");

        service = new WhatsAppMediaService(client, encryption, messageRepository,
                configRepository, mock(org.springframework.beans.factory.ObjectProvider.class));
        ReflectionTestUtils.setField(service, "uploadDir", uploads.toString());
        ReflectionTestUtils.setField(service, "apiBaseUrl", "https://api.xetacrm.pro");
        ReflectionTestUtils.setField(service, "maxBytes", 26214400L);
    }

    private void metaReturns(String mime, long size, byte[] bytes) {
        try {
            when(client.getMediaMetadata(any(), any())).thenReturn(new ObjectMapper().readTree(
                    """
                    {"url":"https://lookaside.fb/media/abc","mime_type":"%s","file_size":%d}"""
                            .formatted(mime, size)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(client.downloadMedia(any(), any())).thenReturn(bytes);
    }

    private WhatsAppMessage storedAfterFetch(String mime, byte[] bytes, String filename) {
        WhatsAppConfig config = new WhatsAppConfig();
        config.setOwnerUserId("owner-1");
        WhatsAppMessage message = WhatsAppMessage.builder().mediaFilename(filename).build();
        when(configRepository.findById(1L)).thenReturn(Optional.of(config));
        when(messageRepository.findById(7L)).thenReturn(Optional.of(message));
        metaReturns(mime, bytes.length, bytes);

        service.fetchAndStore(1L, 7L, "MEDIA-1");
        return message;
    }

    /* ------------------------------------------------------------ the link */

    @Test
    void thereIsNoLinkUntilTheFileIsStored() {
        assertNull(service.publicUrl(WhatsAppMessage.builder().mediaId("MEDIA-1").build()));
        assertNull(service.publicUrl(null));
    }

    @Test
    void theLinkIsThePublicApiHostPlusTheKey() {
        WhatsAppMessage message = WhatsAppMessage.builder().mediaKey("abc123").build();
        assertEquals("https://api.xetacrm.pro/api/public/whatsapp/media/abc123",
                service.publicUrl(message));
    }

    @Test
    void aTrailingSlashOnTheHostDoesNotDoubleUp() {
        ReflectionTestUtils.setField(service, "apiBaseUrl", "https://api.xetacrm.pro/");
        WhatsAppMessage message = WhatsAppMessage.builder().mediaKey("abc123").build();
        assertEquals("https://api.xetacrm.pro/api/public/whatsapp/media/abc123",
                service.publicUrl(message));
    }

    /* --------------------------------------------------------- the fetching */

    @Test
    void thePhotoIsPulledStoredAndGivenAKey() throws Exception {
        byte[] bytes = "jpeg-bytes".getBytes(StandardCharsets.UTF_8);
        WhatsAppMessage message = storedAfterFetch("image/jpeg", bytes, null);

        assertNotNull(message.getMediaKey());
        assertEquals(40, message.getMediaKey().length(), "the key must be long enough to be unguessable");
        assertEquals("image/jpeg", message.getMediaMimeType());
        assertEquals(bytes.length, message.getMediaSize());

        Path stored = Path.of(message.getMediaStoragePath());
        assertTrue(Files.exists(stored));
        assertArrayEquals(bytes, Files.readAllBytes(stored));
        assertTrue(stored.toString().endsWith(".jpg"), stored.toString());
        assertTrue(stored.toString().contains("owner-1"), "files are filed under their workspace");
        verify(messageRepository).save(message);
    }

    @Test
    void aDocumentKeepsItsOwnExtension() {
        WhatsAppMessage message = storedAfterFetch("application/pdf",
                "pdf".getBytes(StandardCharsets.UTF_8), "invoice-1042.pdf");
        assertTrue(message.getMediaStoragePath().endsWith(".pdf"), message.getMediaStoragePath());
        assertEquals("invoice-1042.pdf", message.getMediaFilename());
    }

    @Test
    void everyFileGetsItsOwnKey() {
        WhatsAppMessage first = storedAfterFetch("image/png", "a".getBytes(StandardCharsets.UTF_8), null);
        String firstKey = first.getMediaKey();
        WhatsAppMessage second = storedAfterFetch("image/png", "b".getBytes(StandardCharsets.UTF_8), null);
        assertNotEquals(firstKey, second.getMediaKey());
    }

    @Test
    void anOversizedFileIsLeftOnMetaRatherThanFillingTheDisk() {
        ReflectionTestUtils.setField(service, "maxBytes", 10L);
        WhatsAppConfig config = new WhatsAppConfig();
        config.setOwnerUserId("owner-1");
        WhatsAppMessage message = WhatsAppMessage.builder().build();
        when(configRepository.findById(1L)).thenReturn(Optional.of(config));
        when(messageRepository.findById(7L)).thenReturn(Optional.of(message));
        metaReturns("video/mp4", 50_000_000L, new byte[0]);

        service.fetchAndStore(1L, 7L, "MEDIA-BIG");

        assertNull(message.getMediaKey());
        verify(client, never()).downloadMedia(any(), any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void aFailureAtMetaNeverBreaksTheMessage() {
        WhatsAppConfig config = new WhatsAppConfig();
        config.setOwnerUserId("owner-1");
        WhatsAppMessage message = WhatsAppMessage.builder().build();
        when(configRepository.findById(1L)).thenReturn(Optional.of(config));
        when(messageRepository.findById(7L)).thenReturn(Optional.of(message));
        when(client.getMediaMetadata(any(), any())).thenThrow(new RuntimeException("Meta is down"));

        assertDoesNotThrow(() -> service.fetchAndStore(1L, 7L, "MEDIA-1"));
        assertNull(message.getMediaKey());
    }

    /* --------------------------------------------------------- the serving */

    @Test
    void anUnknownKeyServesNothing() {
        when(messageRepository.findByMediaKey("nope")).thenReturn(Optional.empty());
        assertTrue(service.load("nope").isEmpty());
        assertTrue(service.load(null).isEmpty());
    }

    @Test
    void aStoredFileIsReadBackByItsKey() throws Exception {
        Path file = uploads.resolve("photo.jpg");
        Files.write(file, "bytes".getBytes(StandardCharsets.UTF_8));
        WhatsAppMessage message = WhatsAppMessage.builder()
                .mediaKey("key-1").mediaMimeType("image/jpeg")
                .mediaFilename("photo.jpg").mediaStoragePath(file.toString()).build();
        when(messageRepository.findByMediaKey("key-1")).thenReturn(Optional.of(message));

        WhatsAppMediaService.StoredMedia media = service.load("key-1").orElseThrow();
        assertEquals("image/jpeg", media.mimeType());
        assertEquals("photo.jpg", media.filename());
        assertArrayEquals("bytes".getBytes(StandardCharsets.UTF_8), media.bytes());
    }

    @Test
    void aRowWhoseFileVanishedServesNothing() {
        WhatsAppMessage message = WhatsAppMessage.builder()
                .mediaKey("key-2").mediaStoragePath(uploads.resolve("gone.jpg").toString()).build();
        when(messageRepository.findByMediaKey("key-2")).thenReturn(Optional.of(message));
        assertTrue(service.load("key-2").isEmpty());
    }
}
