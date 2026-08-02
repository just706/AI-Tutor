package com.aitutor.security;

import com.aitutor.exception.UnauthorizedException;

public final class UserContext {

    private static final ThreadLocal<CurrentUser> CURRENT_USER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser currentUser) {
        CURRENT_USER.set(currentUser);
    }

    public static CurrentUser getRequired() {
        CurrentUser currentUser = CURRENT_USER.get();
        if (currentUser == null) {
            throw new UnauthorizedException("未登录或 Token 无效");
        }
        return currentUser;
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
