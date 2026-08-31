package com.sams.auth;

import com.sams.common.ApiException;

public final class RequirePerm {
    private RequirePerm() {}

    public static CurrentUser perm(String perm) {
        CurrentUser u = AuthContext.require();
        if (!u.hasPerm(perm)) {
            throw ApiException.of(403, "forbidden: missing perm '" + perm + "'");
        }
        return u;
    }

    public static CurrentUser admin() {
        CurrentUser u = AuthContext.require();
        if (!u.isAdmin()) throw ApiException.of(403, "forbidden: admin only");
        return u;
    }
}
