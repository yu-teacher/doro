package com.hunnit_beasts.guard.core.dsl.service;

/** 활성 스키마가 바뀌었음을 알린다. 인가 결과 캐시를 무효화하는 데 사용한다. */
public record SchemaChangedEvent() {
}
