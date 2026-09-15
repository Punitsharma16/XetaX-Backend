package com.xetax.crm.settings.controller;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.settings.service.DigestPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Morning digest channels — owner-only saving is enforced in the service. */
@RestController
@RequestMapping("/api/settings/digest")
@RequiredArgsConstructor
public class DigestPreferenceController {

    private final DigestPreferenceService service;

    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        return ResponseUtil.success("Morning digest", service.get());
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        return ResponseUtil.success("Morning digest saved", service.save(
                flag(body, "enabled"), flag(body, "emailEnabled"), flag(body, "whatsappEnabled")));
    }

    private static Boolean flag(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }
}
