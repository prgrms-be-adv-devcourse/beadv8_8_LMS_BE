package com.hapbang.auxiliary.search.in;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 비회원 검색자 키(#883): 공인 주소 판단과 IPv6 /64는 회원 앱(#557)과 같고, 키는 IP 원문이 아닌 HMAC이다 */
class ClientIpKeyTest {

    private final ClientIpKey clientIpKey = new ClientIpKey();

    @ParameterizedTest
    @ValueSource(strings = {"10.42.0.7", "172.16.0.1", "192.168.1.1", "127.0.0.1", "169.254.1.1", "0.0.0.0", "::1",
            "fe80::1", "fd00::1", "localhost", "example.com", ""})
    @DisplayName("사설·루프백·링크 로컬·ULA·와일드카드·이름은 공인 주소가 아니라 키가 없다(IP 한도를 건너뜀)")
    void notPublic(String ip) {
        assertThat(ClientIpKey.isPublicAddress(ip)).isFalse();
        assertThat(clientIpKey.keyOf(ip)).isNull();
    }

    @Test
    @DisplayName("null 주소도 키가 없다")
    void nullAddress() {
        assertThat(clientIpKey.keyOf(null)).isNull();
    }

    @Test
    @DisplayName("공인 주소는 16자 키이고 IP 원문이 들어가지 않는다. 같은 IP는 같은 키, 다른 IP는 다른 키다")
    void publicKey() {
        String key = clientIpKey.keyOf("203.0.113.5");

        assertThat(key).hasSize(16).matches("[0-9a-f]+").doesNotContain("203");
        assertThat(clientIpKey.keyOf("203.0.113.5")).isEqualTo(key);
        assertThat(clientIpKey.keyOf("203.0.113.6")).isNotEqualTo(key);
    }

    @Test
    @DisplayName("IPv6는 같은 /64 안의 주소를 한 키로 센다")
    void ipv6Prefix() {
        assertThat(ClientIpKey.ipLimitKey("2001:db8:1:2:aaaa::1")).isEqualTo("v6/64:2001:0db8:0001:0002");
        assertThat(clientIpKey.keyOf("2001:db8:1:2:aaaa::1")).isEqualTo(clientIpKey.keyOf("2001:db8:1:2:bbbb::9"));
        assertThat(clientIpKey.keyOf("2001:db8:1:3::1")).isNotEqualTo(clientIpKey.keyOf("2001:db8:1:2::1"));
        assertThat(ClientIpKey.ipLimitKey("203.0.113.5")).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("비밀값이 pod(인스턴스)마다 달라 같은 IP라도 다른 pod의 키와 다르다(비밀값 없이 되돌릴 수 없음)")
    void secretPerInstance() {
        assertThat(new ClientIpKey().keyOf("203.0.113.5")).isNotEqualTo(clientIpKey.keyOf("203.0.113.5"));
    }
}
