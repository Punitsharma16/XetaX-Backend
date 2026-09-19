package com.xetax.crm.booking.controller;

import com.xetax.crm.booking.service.AppointmentService;
import com.xetax.crm.common.ratelimit.RateLimiterService;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.menu.service.QrCodeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * The public booking page (no login). The page's key is the whole credential.
 * Booking is rate-limited per visitor and per page, the same way a hosted form
 * is, so a public link cannot be used to sweep a salon's whole diary.
 */
@RestController
@RequestMapping("/api/public/booking")
@RequiredArgsConstructor
public class PublicBookingController {

    private final AppointmentService appointmentService;
    private final QrCodeService qrCodeService;
    private final RateLimiterService rateLimiter;

    @Value("${app.public-base-url:http://localhost:5000}")
    private String publicBaseUrl;

    @GetMapping("/{key}")
    public ApiResponse<Map<String, Object>> page(@PathVariable String key) {
        return ResponseUtil.success("Booking", appointmentService.storefront(key));
    }

    @PostMapping("/{key}/book")
    public ApiResponse<Map<String, Object>> book(@PathVariable String key,
                                                 @RequestBody AppointmentService.BookRequest request,
                                                 HttpServletRequest http) {
        String ip = clientIp(http);
        rateLimiter.check("booking:" + key + ":" + ip, 6, Duration.ofMinutes(5));
        rateLimiter.check("booking-total:" + key, 300, Duration.ofHours(1));
        return ResponseUtil.success("Booked", appointmentService.book(key, request));
    }

    /** The QR code for the booking page — printable before the page goes live. */
    @GetMapping(value = "/{key}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@PathVariable String key,
                                     @RequestParam(defaultValue = "512") int size,
                                     @RequestParam(required = false) Boolean download) {
        String link = publicBaseUrl.trim().replaceAll("/+$", "") + "/book/" + key;
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (Boolean.TRUE.equals(download) ? "attachment" : "inline") + "; filename=\"booking-qr.png\"")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)))
                .body(qrCodeService.png(link, size));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
