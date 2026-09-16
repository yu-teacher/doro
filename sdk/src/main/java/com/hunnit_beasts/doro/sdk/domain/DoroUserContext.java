package com.hunnit_beasts.doro.sdk.domain;

public final class DoroUserContext {

    private static final ThreadLocal<DoroUser> CURRENT_USER = new ThreadLocal<>();

    private DoroUserContext() {}

    public static void setCurrentUser(DoroUser user) {
        CURRENT_USER.set(user);
    }

    public static DoroUser getCurrentUser() {
        DoroUser user = CURRENT_USER.get();
        return user != null ? user : DoroUser.anonymous();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
