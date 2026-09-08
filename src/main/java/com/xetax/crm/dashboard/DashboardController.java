package com.xetax.crm.dashboard;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Workspace overview for the dashboard. No @RequiresPermission on purpose —
 * every signed-in member gets a dashboard; the service trims record numbers
 * to what the caller's role may see.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ResponseUtil.success("Dashboard summary", dashboardService.summary());
    }
}
