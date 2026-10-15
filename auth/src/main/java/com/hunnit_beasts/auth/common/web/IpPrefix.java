package com.hunnit_beasts.auth.common.web;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 속도 제한·로그인 잠금이 클라이언트를 구분하는 키. IPv4 는 주소 그대로, IPv6 는 앞 64비트(/64)로 묶는다.
 * IPv6 사용자는 보통 /64 하나를 통째로 받으므로(주소를 마음대로 바꿀 수 있다), 주소 전체를 키로 쓰면 한 사람이 제한을 피해 갈 수 있다.
 * IPv4 로 매핑된 IPv6 주소(::ffff:1.2.3.4)는 IPv4 로 본다. 해석할 수 없는 값은 그대로 돌려준다(예: "UNKNOWN").
 */
public final class IpPrefix {

    private IpPrefix() {}

    public static String rateLimitKey(String ip) {
        if (ip == null || ip.indexOf(':') < 0) {
            return ip;
        }
        try {
            // 리터럴 주소만 해석한다(DNS 조회 없음). 영문자·콜론·점·숫자 이외가 섞인 값은 호스트 이름일 수 있어 건드리지 않는다.
            if (!ip.matches("[0-9a-fA-F:.]+(%[0-9A-Za-z._-]+)?")) {
                return ip;
            }
            byte[] bytes = InetAddress.getByName(stripZone(ip)).getAddress();
            if (bytes.length == 4) {
                // IPv4 매핑 주소는 JDK 가 4바이트로 돌려준다
                return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + "." + (bytes[3] & 0xff);
            }
            return String.format("%02x%02x:%02x%02x:%02x%02x:%02x%02x::/64",
                    bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7]);
        } catch (UnknownHostException | RuntimeException e) {
            return ip;
        }
    }

    private static String stripZone(String ip) {
        int zone = ip.indexOf('%');
        return zone < 0 ? ip : ip.substring(0, zone);
    }
}
