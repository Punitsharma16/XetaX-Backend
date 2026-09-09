package com.xetax.crm.template.dto;

import com.xetax.crm.template.model.PackDefinition;

import java.time.LocalDateTime;
import java.util.Map;

/** Gallery row: the pack itself + what it includes + whether this workspace already installed it. */
public record PackView(PackDefinition pack, boolean builtin, Long customId, String source,
                       boolean installed, Long installedFormId, LocalDateTime installedAt,
                       Map<String, Object> includes) {}
