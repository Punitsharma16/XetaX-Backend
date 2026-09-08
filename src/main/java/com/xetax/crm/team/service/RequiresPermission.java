package com.xetax.crm.team.service;

import java.lang.annotation.*;

/**
 * Gate a controller method or AI tool with one catalog permission key.
 * Org owners always pass; members pass only if their role grants the key.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {
    String value();
}
