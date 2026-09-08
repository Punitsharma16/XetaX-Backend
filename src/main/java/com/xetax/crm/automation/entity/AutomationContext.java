package com.xetax.crm.automation.entity;

import com.xetax.crm.automation.enums.AutomationTrigger;
import com.xetax.crm.data_manager.entity.FormEntity;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AutomationContext {

    private AutomationTrigger trigger;

    private FormEntity form;

    private Record record;

}
