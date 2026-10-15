package com.hunnit_beasts.auth.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 속도 제한·로그인 잠금의 클라이언트 키: IPv4 는 그대로, IPv6 는 /64 로 묶는다. */
class IpPrefixTest {

    @Test
    @DisplayName("IPv4 는 주소 그대로다")
    void ipv4IsUnchanged() {
        assertThat(IpPrefix.rateLimitKey("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("같은 /64 의 IPv6 주소는 같은 키, 다른 /64 는 다른 키")
    void sameSlash64SharesAKey() {
        String a = IpPrefix.rateLimitKey("2001:db8:1:2::1");
        String b = IpPrefix.rateLimitKey("2001:0db8:0001:0002:abcd:ef01:2345:6789");
        String c = IpPrefix.rateLimitKey("2001:db8:1:3::1");

        assertThat(a).isEqualTo(b).isEqualTo("2001:0db8:0001:0002::/64");
        assertThat(c).isNotEqualTo(a);
    }

    @Test
    @DisplayName("IPv4 로 매핑된 IPv6 주소는 IPv4 로 본다")
    void mappedIpv4() {
        assertThat(IpPrefix.rateLimitKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("범위 식별자(%eth0)가 붙어도 같은 /64 로 묶는다")
    void zoneIdIsIgnored() {
        assertThat(IpPrefix.rateLimitKey("fe80::1%eth0")).isEqualTo(IpPrefix.rateLimitKey("fe80::2"));
    }

    @Test
    @DisplayName("해석할 수 없는 값·null·호스트 이름 비슷한 값은 건드리지 않는다(DNS 조회도 하지 않는다)")
    void unparsableValuesPassThrough() {
        assertThat(IpPrefix.rateLimitKey("UNKNOWN")).isEqualTo("UNKNOWN");
        assertThat(IpPrefix.rateLimitKey(null)).isNull();
        assertThat(IpPrefix.rateLimitKey("not:an:ip:zzz")).isEqualTo("not:an:ip:zzz");
        assertThat(IpPrefix.rateLimitKey("example.com:8080")).isEqualTo("example.com:8080");
    }
}
