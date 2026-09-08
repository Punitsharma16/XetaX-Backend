package com.xetax.crm.autopilot;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/autopilot")
@RequiredArgsConstructor
public class AutopilotController {

    private final AutopilotService autopilotService;
    private final CurrentUserProvider currentUserProvider;

    /** "Send my digest now" — also how the digest is demoed/tested. */
    @PostMapping("/run-now")
    public ApiResponse<Map<String, Object>> runNow() {
        UUID owner = currentUserProvider.currentDataOwnerIdOrNull();
        if (owner == null) throw new BadRequestException("Not signed in");
        return ResponseUtil.success("Digest", Map.of("digest", autopilotService.runFor(owner.toString())));
    }
}
