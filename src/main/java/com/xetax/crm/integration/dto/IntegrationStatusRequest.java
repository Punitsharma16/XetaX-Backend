package com.xetax.crm.integration.dto;

import com.xetax.crm.integration.enums.IntegrationStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Switches an integration on or off.
 *
 * <p>Only ACTIVE and DISABLED are accepted. PENDING is not a choice — it means
 * "no mapping saved yet" and only the mapping step can clear it.
 */
@Data
public class IntegrationStatusRequest {

    @NotNull
    private IntegrationStatus status;

}
