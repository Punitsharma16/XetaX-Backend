package com.xetax.crm.whatsapp.controller;

import com.xetax.crm.whatsapp.service.WhatsAppMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * The shareable link for a photo, video, voice note or document a customer
 * sent on WhatsApp.
 *
 * <p>Deliberately public and unauthenticated — that is what makes it
 * shareable — so the key in the path is the whole secret: 40 random hex
 * characters, minted per file, stored on the message row. Nothing about the
 * key reveals the workspace, the customer or the conversation, and it is the
 * only way in. Anyone holding the link can open the file, so treat it the way
 * you would treat the file itself.
 */
@RestController
@RequestMapping("/api/public/whatsapp/media")
@RequiredArgsConstructor
public class WhatsAppMediaController {

    private final WhatsAppMediaService mediaService;

    /** Opens in the browser; add ?download=true to save it instead. */
    @GetMapping("/{key}")
    public ResponseEntity<byte[]> get(@PathVariable String key,
                                      @RequestParam(required = false) Boolean download) {
        return mediaService.load(key)
                .map(media -> ResponseEntity.ok()
                        .contentType(mediaType(media.mimeType()))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                (Boolean.TRUE.equals(download) ? "attachment" : "inline")
                                        + "; filename=\"" + media.filename().replace("\"", "") + "\"")
                        // The bytes never change once stored, and the key is
                        // unguessable, so a long private cache is safe.
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                        .body(media.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static MediaType mediaType(String mime) {
        try {
            return MediaType.parseMediaType(mime);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
