package com.hunnit_beasts.guard.common.validation;

/** 튜플/스키마 검증 강도. OFF: 검사 안 함, WARN: 로그만, ENFORCE: 요청 거부. */
public enum ValidationMode {
    OFF, WARN, ENFORCE
}
