package com.xetax.crm.settings.controller;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.settings.service.OrgSmtpService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Panel-managed email settings — admin-gated inside the service. */
@RestController
@RequestMapping("/api/settings/smtp")
@RequiredArgsConstructor
public class OrgSmtpController {

    private final OrgSmtpService smtpService;

    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        return ResponseUtil.success("SMTP settings", smtpService.get());
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        return ResponseUtil.success("SMTP saved", smtpService.save(
                (String) body.get("host"),
                body.get("port") == null ? null : Integer.valueOf(String.valueOf(body.get("port"))),
                (String) body.get("username"),
                (String) body.get("password")));
    }

    @DeleteMapping
    public ApiResponse<Void> delete() {
        smtpService.delete();
        return ResponseUtil.success("SMTP removed");
    }

    @PostMapping("/test")
    public ApiResponse<Map<String, Object>> test(@RequestBody Map<String, String> body) {
        return ResponseUtil.success("Test", smtpService.sendTest(body.get("to")));
    }
}
