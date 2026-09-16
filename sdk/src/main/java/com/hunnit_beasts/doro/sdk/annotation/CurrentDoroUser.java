package com.hunnit_beasts.doro.sdk.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 현재 로그인한 DoroUser 또는 유저 ID(UUID)를 컨트롤러 파라미터에 자동 주입
 * <p>
 * 예시:
 * {@code public Response getDoc(@CurrentDoroUser DoroUser user) { ... }}
 * {@code public Response getDoc(@CurrentDoroUser UUID userId) { ... }}
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentDoroUser {
}
