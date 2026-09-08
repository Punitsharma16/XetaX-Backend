package com.xetax.crm.notification;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** The bell. Personal to the signed-in user — no permission gating. */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<Map<String, Object>> feed() {
        return ResponseUtil.success("Notifications", notificationService.myFeed());
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable Long id) {
        notificationService.markRead(id);
        return ResponseUtil.success("Read");
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> readAll() {
        notificationService.markAllRead();
        return ResponseUtil.success("All read");
    }
}
