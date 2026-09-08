package com.xetax.crm.team.service;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * Enforces {@link RequiresPermission} on any Spring bean method — REST
 * controllers AND AI tools go through the same gate, so the assistant can
 * never do something the member's role forbids.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    private final PermissionService permissionService;

    @Before("@annotation(requiresPermission)")
    public void check(RequiresPermission requiresPermission) {
        permissionService.require(requiresPermission.value());
    }
}
