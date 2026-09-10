package com.xetax.crm.platform;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.auth.user.AuthUserEntity;
import com.xetax.crm.common.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The only door into the platform console. A workspace admin is NOT a platform
 * admin — that flag is set by PLATFORM_ADMIN_EMAILS on the server or by another
 * platform admin, never through a customer-facing screen.
 */
@Component
@RequiredArgsConstructor
public class PlatformAdminGuard {

    private final CurrentUserProvider currentUserProvider;

    public AuthUserEntity require() {
        AuthUserEntity user = currentUserProvider.currentUserOrNull();
        if (user == null || !Boolean.TRUE.equals(user.getPlatformAdmin())) {
            throw new UnauthorizedException("Platform administrators only");
        }
        return user;
    }

    public boolean is() {
        AuthUserEntity user = currentUserProvider.currentUserOrNull();
        return user != null && Boolean.TRUE.equals(user.getPlatformAdmin());
    }
}
