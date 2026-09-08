package com.xetax.crm.desk;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Agent detail → "Channels & Pipeline" tab. */
@RestController
@RequestMapping("/api/agents/{agentId}/channels")
@RequiredArgsConstructor
public class ChannelConfigController {

    private final ChannelConfigService service;

    @GetMapping
    @RequiresPermission("agents.manage")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long agentId) {
        return ResponseUtil.success("Channel settings", service.get(agentId));
    }

    @PutMapping
    @RequiresPermission("agents.manage")
    public ApiResponse<Map<String, Object>> save(@PathVariable Long agentId,
                                                 @RequestBody ChannelConfigService.SaveRequest body) {
        return ResponseUtil.success("Channel settings saved", service.save(agentId, body));
    }
}
