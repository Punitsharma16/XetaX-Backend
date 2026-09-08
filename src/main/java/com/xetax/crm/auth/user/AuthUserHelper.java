package com.xetax.crm.auth.user;

import java.util.UUID;

public class AuthUserHelper {
    public static UUID parseUUID(String userId) {
        return UUID.fromString(userId);
    }
}
