package com.sams.auth;

import java.util.List;
import java.util.Map;

public record CurrentUser(
        long userId,
        String username,
        long roleId,
        boolean isAdmin,
        Map<String, Boolean> perms,
        List<String> scopes,
        boolean mustChangePassword,
        String jti
) {
    public boolean hasPerm(String perm) {
        if (isAdmin) return true;
        return Boolean.TRUE.equals(perms.get(perm));
    }

    public List<String> visibleScopes() {
        return ScopeService.visibleScopes(this);
    }
}
