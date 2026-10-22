package com.hunnit_beasts.auth.domain.social.provider;

/** 소셜 로그인 실패. code 는 로그인 화면으로 돌려보낼 때 쓰는 짧은 코드이고, 화면이 문구로 바꾼다. */
public class SocialLoginException extends RuntimeException {

    public static final String INVALID_STATE = "invalid_state";
    public static final String PROVIDER_ERROR = "provider_error";
    public static final String EMAIL_NOT_VERIFIED = "email_not_verified";
    public static final String ACCOUNT_SUSPENDED = "account_suspended";
    public static final String DENIED = "denied";
    public static final String UNAVAILABLE = "unavailable";
    public static final String TOO_MANY = "too_many";

    private final String code;

    public SocialLoginException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SocialLoginException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
