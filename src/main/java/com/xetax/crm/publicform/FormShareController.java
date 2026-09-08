package com.xetax.crm.publicform;

import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.common.responce.ApiResponse;
import com.xetax.crm.common.responce.ResponseUtil;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.service.OwnershipGuard;
import com.xetax.crm.team.service.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Share & Webhook" tab of the form page: toggle the public form, get the
 * hosted link / iframe snippet / webhook URL, regenerate the key (kills old
 * links instantly).
 */
@RestController
@RequestMapping("/api/forms/{formId}/share")
@RequiredArgsConstructor
public class FormShareController {

    private final FormRepo formRepo;
    private final OwnershipGuard ownershipGuard;

    @Value("${app.public-base-url}")
    private String publicBaseUrl;

    @Value("${app.api-base-url}")
    private String apiBaseUrl;

    @GetMapping
    @RequiresPermission("forms.view")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long formId) {
        return ResponseUtil.success("Share settings", view(owned(formId)));
    }

    public record ShareUpdate(Boolean enabled, Boolean regenerateKey) {}

    @PutMapping
    @RequiresPermission("forms.manage")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long formId,
                                                   @RequestBody ShareUpdate update) {
        FormEntity form = owned(formId);
        if (Boolean.TRUE.equals(update.regenerateKey()) || form.getPublicKey() == null) {
            form.setPublicKey(UUID.randomUUID().toString().replace("-", ""));
        }
        if (update.enabled() != null) {
            form.setPublicEnabled(update.enabled());
        }
        formRepo.save(form);
        return ResponseUtil.success("Share settings saved", view(form));
    }

    private FormEntity owned(Long formId) {
        FormEntity form = formRepo.findById(formId)
                .orElseThrow(() -> new ResourceNotFoundException("Form Not Found"));
        ownershipGuard.assertOwned(form);
        return form;
    }

    private Map<String, Object> view(FormEntity form) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean enabled = Boolean.TRUE.equals(form.getPublicEnabled());
        out.put("enabled", enabled);
        String key = form.getPublicKey();
        if (key != null) {
            out.put("publicKey", key);
            out.put("formUrl", publicBaseUrl + "/f/" + key);
            out.put("webhookUrl", apiBaseUrl + "/api/public/forms/" + key + "/submit");
        }
        return out;
    }
}
