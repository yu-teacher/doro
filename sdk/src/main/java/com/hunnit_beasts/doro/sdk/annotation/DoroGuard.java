package com.hunnit_beasts.doro.sdk.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Doro Guard ReBAC 인가 어노테이션
 * <p>
 * 사용 예시 1 (속성 지정):
 * {@code @DoroGuard(namespace = "document", object = "#docId", relation = "viewer")}
 * <p>
 * 사용 예시 2 (단축형 SpEL 표현식):
 * {@code @DoroGuard("document:#docId#editor")}
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DoroGuard {

    /**
     * 단축형 표현식: "namespace:object#relation" (예: "document:#id#viewer")
     */
    String value() default "";

    /**
     * 네임스페이스 (예: "document", "folder", "workspace")
     */
    String namespace() default "";

    /**
     * 객체 ID (SpEL 지원, 예: "#docId", "#request.targetId")
     */
    String object() default "";

    /**
     * 검사할 릴레이션 권한 (예: "viewer", "editor", "owner")
     */
    String relation() default "";

    /**
     * 대상 사용자 ID (SpEL 지원, 비어있을 시 현재 로그인한 DoroUser의 userId 자동 바인딩)
     */
    String subject() default "";
}
