package com.hunnit_beasts.auth.domain.user.dto;

/** 첫 관리자 부트스트랩 요청. 토큰은 로그에 남지 않도록 toString 에서 가린다. */
public record BootstrapRequest(String token) {
    @Override
    public String toString() {
        return "BootstrapRequest[token=***]";
    }
}
