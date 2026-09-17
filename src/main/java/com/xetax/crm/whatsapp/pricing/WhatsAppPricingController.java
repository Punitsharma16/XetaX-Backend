package com.xetax.crm.whatsapp.pricing;

import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/** Our WhatsApp price list and estimates, for the panel. */
@RestController
@RequestMapping("/api/whatsapp")
@RequiredArgsConstructor
public class WhatsAppPricingController {

    private final WhatsAppRateService rateService;
    private final WhatsAppChargeService chargeService;

    /** Today's India prices and the next change — Meta's public card, so any signed-in user may read it. */
    @GetMapping("/rates")
    public ApiResponse<Map<String, Object>> rates() {
        return ResponseUtil.success("Rates", rateService.summary(LocalDate.now(chargeService.zone())));
    }

    @GetMapping("/campaigns/{id}/estimate")
    @RequiresPermission("whatsapp.campaigns")
    public ApiResponse<Map<String, Object>> campaignEstimate(@PathVariable Long id) {
        return ResponseUtil.success("Estimate", chargeService.campaignEstimate(id));
    }
}
