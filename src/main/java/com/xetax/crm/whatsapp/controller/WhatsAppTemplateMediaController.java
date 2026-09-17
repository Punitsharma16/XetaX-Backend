package com.xetax.crm.whatsapp.controller;

import com.xetax.crm.whatsapp.service.WhatsAppTemplateMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * The public link a template's header file is sent from. Meta fetches it on
 * every send, so it needs no login; the key in the path is the only credential.
 */
@RestController
@RequestMapping("/api/public/whatsapp/template-media")
@RequiredArgsConstructor
public class WhatsAppTemplateMediaController {

    private final WhatsAppTemplateMediaService mediaService;

    @GetMapping("/{file:.+}")
    public ResponseEntity<byte[]> get(@PathVariable String file) {
        return mediaService.load(file)
                .map(stored -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(stored.mimeType()))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=\"" + stored.filename().replace("\"", "") + "\"")
                        .header("X-Content-Type-Options", "nosniff")
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(30)))
                        .body(stored.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
