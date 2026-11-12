package com.hapbang.auxiliary.search.in;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 비회원 검색자를 가리키는 키(#883 L18·L19, Q2·Q3·Q5). rate limit 키와 0건 검색어의 검색자 키에 함께 쓴다.
 * <ul>
 *     <li>입력은 {@code request.getRemoteAddr()}만이다. 운영은 Tomcat {@code RemoteIpValve}가 Envoy가 붙인 실제 접속 주소를 넣고, 클라이언트가
 *     보낸 {@code X-Forwarded-For}는 무시된다(#726 실측, {@code docs/architecture/infrastructure/api-gateway.md}).
 *     {@code X-Forwarded-For}·{@code X-Real-IP}·{@code X-Envoy-External-Address}는 위조할 수 있어 읽지 않는다.</li>
 *     <li>IPv6는 앞 64비트(/64)로 센다. 보통 한 회선에 /64 묶음을 주므로 주소를 바꿔 가며 한도를 피하지 못하게 한다.</li>
 *     <li>키는 IP 원문이 아니라 HMAC-SHA256(pod마다 무작위 비밀값, IP) 앞 16자다(Q5). 로그·메모리에 IP가 남지 않고, 비밀값을 모르면 IPv4
 *     전체를 대입해도 되돌릴 수 없다. pod가 다시 뜨면 값이 바뀌지만 한도·0건 검색어도 pod 메모리라 상관없다.</li>
 *     <li>공인 주소가 아니면(사설·루프백·링크 로컬 등) 키를 주지 않는다(Q2). Envoy 앞 경로가 깨져 모두가 같은 내부 주소로 보이는 신호라 IP 한도를
 *     건너뛴다. 처음 한 번과 그 뒤 {@value #WARN_INTERVAL_MINUTES}분마다 한 번 WARN을 남긴다(내부 주소는 개인정보가 아니라 남긴다).</li>
 * </ul>
 * {@link #ipLimitKey}·{@link #isPublicAddress}는 회원 앱 {@code MemberEmailVerificationUseCase}(#557)를 그대로 옮긴 것이다(Q3). 두 앱이
 * 같은 규칙이어야 하므로 한쪽을 고치면 다른 쪽도 고친다. 공용화(common)는 rate limit 공용화 판단(#697) 때 함께 본다. 회원 앱과 같이 CGNAT 대역
 * {@code 100.64/10}은 공인으로 본다(Tomcat은 내부 proxy로 건너뛰지만 우리 cluster 대역은 10.42·10.43이라 생기지 않는다).
 */
@Slf4j
@Component
public class ClientIpKey {

    static final int WARN_INTERVAL_MINUTES = 10;
    private static final long WARN_INTERVAL_NANOS = Duration.ofMinutes(WARN_INTERVAL_MINUTES).toNanos();
    private static final int KEY_HEX_LENGTH = 16;

    private final SecretKeySpec secret;
    private final ThreadLocal<Mac> macs;
    private final AtomicLong skippedSinceWarn = new AtomicLong();
    /** 다음 WARN을 남길 수 있는 시각(System.nanoTime). 0이면 아직 남긴 적 없음 */
    private final AtomicLong nextWarnAt = new AtomicLong();

    public ClientIpKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        this.secret = new SecretKeySpec(key, "HmacSHA256");
        this.macs = ThreadLocal.withInitial(this::newMac);
    }

    /**
     * @param remoteAddr {@code request.getRemoteAddr()}
     * @return 검색자 키. 공인 주소가 아니면 null(IP 한도를 건너뛰고 0건 검색어에서도 세지 않는다)
     */
    public String keyOf(String remoteAddr) {
        if (!isPublicAddress(remoteAddr)) {
            warnNotPublic(remoteAddr);
            return null;
        }
        byte[] digest = macs.get().doFinal(ipLimitKey(remoteAddr).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).substring(0, KEY_HEX_LENGTH);
    }

    private void warnNotPublic(String remoteAddr) {
        long skipped = skippedSinceWarn.incrementAndGet();
        long now = System.nanoTime();
        long next = nextWarnAt.get();
        if ((next == 0 || now - next >= 0) && nextWarnAt.compareAndSet(next, now + WARN_INTERVAL_NANOS)) {
            skippedSinceWarn.addAndGet(-skipped);
            log.warn("비회원 client IP가 공인 주소가 아니라 IP 호출 제한을 건너뛰었다. Envoy가 진짜 접속 IP를 넘기는지 확인한다 ip={}, skipped={}",
                    remoteAddr, skipped);
        }
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secret);
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256을 만들지 못했습니다", e);
        }
    }

    /**
     * IP 제한을 세는 단위. IPv4는 주소 그대로, IPv6는 앞 64비트(/64)다. 공인 주소로 확인된 값만 넘긴다(회원 앱과 같다)
     */
    static String ipLimitKey(String ip) {
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
     * 숫자 주소만 받는다(이름을 넘기면 DNS 조회를 하지 않고 false). 회원 앱과 같다
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
