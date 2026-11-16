package com.hapbang.member.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * IP별 제한을 세는 단위 (이메일 인증 #557, 로그인 #506).
 * <ul>
 *     <li>입력은 {@code request.getRemoteAddr()}만이다. 운영은 Tomcat {@code RemoteIpValve}가 Envoy가 붙인 실제 접속 주소를 넣고, 클라이언트가
 *     보낸 {@code X-Forwarded-For}는 무시된다({@code docs/architecture/infrastructure/api-gateway.md} rate limit).
 *     {@code X-Forwarded-For}·{@code X-Real-IP}·{@code X-Envoy-External-Address}는 위조할 수 있어 읽지 않는다.</li>
 *     <li>IPv4는 주소 그대로, IPv6는 앞 64비트(/64)다. IPv6는 보통 한 사용자·회선에 /64 묶음을 주므로 주소 하나씩 세면 그 안에서 주소를
 *     바꿔 가며 제한을 피할 수 있다(#557 리뷰).</li>
 *     <li>공인 주소가 아니면(사설·루프백·링크 로컬 등) 키를 만들지 않는다. Envoy 앞 경로가 깨져 모두가 같은 내부 주소로 보이는 신호라
 *     IP 제한을 건너뛴다(쓰는 쪽이 WARN을 남긴다).</li>
 * </ul>
 * auxiliary-app 검색의 {@code ClientIpKey}(#883)와 같은 규칙이다. 두 앱이 같은 규칙이어야 하므로 한쪽을 고치면 다른 쪽도 고친다.
 * 공용화(common)는 rate limit 공용화 판단(#697) 때 함께 본다.
 *
 * @param value 세는 단위 문자열 (IPv4 주소, 또는 {@code v6/64:xxxx:xxxx:xxxx:xxxx})
 */
public record ClientIpKey(String value) {

    /**
     * @param remoteAddr {@code request.getRemoteAddr()}
     * @return 공인 주소면 세는 단위, 아니면 비어 있음
     */
    public static Optional<ClientIpKey> from(String remoteAddr) {
        if (!isPublicAddress(remoteAddr)) {
            return Optional.empty();
        }
        return Optional.of(new ClientIpKey(limitKey(remoteAddr)));
    }

    /** IPv4는 주소 그대로, IPv6는 앞 64비트. 공인 주소로 확인된 값만 넘긴다 */
    static String limitKey(String ip) {
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length != 16) {
                return ip;
            }
            StringBuilder prefix = new StringBuilder("v6/64:");
            for (int i = 0; i < 8; i += 2) {
                prefix.append(String.format("%02x%02x", bytes[i], bytes[i + 1]));
                if (i < 6) {
                    prefix.append(':');
                }
            }
            return prefix.toString();
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    /**
     * 인터넷 주소인지. 사설(10/8, 172.16/12, 192.168/16, fc00::/7)·루프백·링크 로컬·와일드카드면 false.
     * 숫자 주소만 받는다(이름을 넘기면 DNS 조회를 하지 않고 false)
     */
    static boolean isPublicAddress(String ip) {
        // 숫자 IPv4이거나 콜론이 있는 IPv6만 주소로 본다. 그 밖의 값(이름)은 InetAddress가 DNS를 조회하므로 넘기지 않는다
        boolean ipv4 = ip != null && ip.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean ipv6 = ip != null && ip.contains(":") && ip.matches("[0-9a-fA-F:.]+");
        if (!ipv4 && !ipv6) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            byte[] bytes = address.getAddress();
            boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
            return !(address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress() || uniqueLocalV6);
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
