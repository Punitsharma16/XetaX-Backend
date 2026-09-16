package com.xetax.crm.menu.controller;

import com.xetax.crm.common.ratelimit.RateLimiterService;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.menu.service.MenuOrderService;
import com.xetax.crm.menu.service.QrCodeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * The public menu (no login). The store's key is the whole credential.
 * Ordering is rate-limited per visitor and per menu, the same way a hosted
 * form is, so a public link cannot be used to flood the order pipeline.
 */
@RestController
@RequestMapping("/api/public/menu")
@RequiredArgsConstructor
public class PublicMenuController {

    private final MenuOrderService orderService;
    private final QrCodeService qrCodeService;
    private final RateLimiterService rateLimiter;

    @Value("${app.public-base-url:http://localhost:5000}")
    private String publicBaseUrl;

    @GetMapping("/{key}")
    public ApiResponse<Map<String, Object>> storefront(@PathVariable String key) {
        return ResponseUtil.success("Menu", orderService.storefront(key));
    }

    @PostMapping("/{key}/orders")
    public ApiResponse<Map<String, Object>> placeOrder(@PathVariable String key,
                                                       @RequestBody MenuOrderService.OrderRequest order,
                                                       HttpServletRequest request) {
        String ip = clientIp(request);
        rateLimiter.check("menu-order:" + key + ":" + ip, 6, Duration.ofMinutes(5));
        rateLimiter.check("menu-order-total:" + key, 600, Duration.ofHours(1));
        return ResponseUtil.success("Order placed", orderService.placeOrder(key, order));
    }

    /**
     * The QR code for the menu, or for one table when ?table= is given. Works
     * before the menu is switched on, so it can be printed in advance.
     */
    @GetMapping(value = "/{key}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@PathVariable String key,
                                     @RequestParam(required = false) String table,
                                     @RequestParam(defaultValue = "512") int size,
                                     @RequestParam(required = false) Boolean download) {
        if (!orderService.exists(key)) return ResponseEntity.notFound().build();
        String cleanTable = table == null ? null : table.trim().replaceAll("[^A-Za-z0-9 _-]", "");
        if (cleanTable != null && (cleanTable.isEmpty() || cleanTable.length() > 20)) cleanTable = null;

        String link = publicBaseUrl.trim().replaceAll("/+$", "") + "/menu/" + key
                + (cleanTable == null ? "" : "?table=" + URLEncoder.encode(cleanTable, StandardCharsets.UTF_8));
        String filename = cleanTable == null ? "menu-qr.png" : "menu-qr-table-" + cleanTable.replace(' ', '-') + ".png";

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (Boolean.TRUE.equals(download) ? "attachment" : "inline") + "; filename=\"" + filename + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)))
                .body(qrCodeService.png(link, size));
    }

    @GetMapping("/images/{imageKey}")
    public ResponseEntity<byte[]> image(@PathVariable String imageKey) {
        return orderService.image(imageKey)
                .map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.mimeType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(7)))
                        .body(image.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
