package com.xetax.crm.publicform;

import com.xetax.crm.common.ratelimit.RateLimiterService;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Public endpoints (no JWT): hosted form page + incoming webhook. Rate-limited
 * per IP and per form so a public link can't be used to flood the pipeline.
 */
@RestController
@RequestMapping("/api/public/forms")
@RequiredArgsConstructor
public class PublicFormController {

    private final PublicFormService publicFormService;
    private final RateLimiterService rateLimiter;

    @GetMapping("/{publicKey}")
    public ApiResponse<Map<String, Object>> info(@PathVariable String publicKey) {
        return ResponseUtil.success("Form", publicFormService.info(publicKey));
    }

    @PostMapping("/{publicKey}/submit")
    public ApiResponse<Map<String, Object>> submit(@PathVariable String publicKey,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        String ip = clientIp(request);
        rateLimiter.check("pubform:" + publicKey + ":" + ip, 10, Duration.ofMinutes(1));
        rateLimiter.check("pubform-total:" + publicKey, 300, Duration.ofHours(1));
        return ResponseUtil.success("Submitted", publicFormService.submit(publicKey, body));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
