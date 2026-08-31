package com.sams.auth;

public final class AuthContext {
    private static final ThreadLocal<CurrentUser> TL = new ThreadLocal<>();

    private AuthContext() {}

    public static void set(CurrentUser user) {
        TL.set(user);
    }

    public static CurrentUser get() {
        return TL.get();
    }

    public static CurrentUser require() {
        CurrentUser u = TL.get();
        if (u == null) throw com.sams.common.ApiException.of(401, "unauthenticated");
        return u;
    }

    public static void clear() {
        TL.remove();
    }
}
