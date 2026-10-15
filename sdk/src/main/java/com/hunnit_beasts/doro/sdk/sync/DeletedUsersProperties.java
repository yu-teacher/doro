package com.hunnit_beasts.doro.sdk.sync;

import java.time.Duration;

/**
 * 탈퇴 동기화 설정. 서비스가 자기 접두사(예: {@code blog.deleted-sync})로 바인딩한 빈을 등록한다.
 * IAM 내부 API 주소와 서비스 토큰은 {@code doro.iam.internal-url}, {@code doro.iam.internal-token} 에서 읽는다.
 */
public class DeletedUsersProperties {

    private boolean enabled = true;
    private int pageSize = 200;
    /** 서로 다른 트랜잭션이 거의 동시에 커밋돼 순서가 뒤바뀌어도 놓치지 않도록, 저장하는 기준 시각을 이만큼 앞당긴다. */
    private Duration overlap = Duration.ofMinutes(10);
    private Duration interval = Duration.ofHours(1);
    private Duration initialDelay = Duration.ofMinutes(2);
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(10);
    /** 이 동기화의 이름(저장하는 기준 시각의 키). 한 서비스에 하나면 기본값을 쓴다. */
    private String name = "iam-deleted-users";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public Duration getOverlap() { return overlap; }
    public void setOverlap(Duration overlap) { this.overlap = overlap; }
    public Duration getInterval() { return interval; }
    public void setInterval(Duration interval) { this.interval = interval; }
    public Duration getInitialDelay() { return initialDelay; }
    public void setInitialDelay(Duration initialDelay) { this.initialDelay = initialDelay; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
