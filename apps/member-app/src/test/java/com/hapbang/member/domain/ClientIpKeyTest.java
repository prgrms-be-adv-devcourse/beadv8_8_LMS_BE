package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientIpKeyTest {

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.5", "8.8.8.8", "2001:4860:4860::8888"})
    @DisplayName("인터넷 주소는 공인 주소로 보고 키를 만든다")
    void publicAddresses(String ip) {
        assertThat(ClientIpKey.isPublicAddress(ip)).isTrue();
        assertThat(ClientIpKey.from(ip)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.42.0.15", "172.16.0.1", "192.168.1.10", "127.0.0.1", "169.254.169.254", "::1",
            "fd00::1", "fe80::1", "0.0.0.0", "", "localhost", "evil.example.com", "dead.beef"})
    @DisplayName("사설·루프백·링크 로컬·빈 값·이름은 공인 주소로 보지 않아 키가 없다(이름은 DNS 조회도 하지 않는다)")
    void nonPublicAddresses(String ip) {
        assertThat(ClientIpKey.isPublicAddress(ip)).isFalse();
        assertThat(ClientIpKey.from(ip)).isEmpty();
    }

    @Test
    @DisplayName("null은 키가 없다")
    void nullAddress() {
        assertThat(ClientIpKey.from(null)).isEmpty();
    }

    @Test
    @DisplayName("세는 단위: IPv4는 주소 그대로, IPv6는 같은 /64면 같은 것으로 센다")
    void limitKey() {
        assertThat(ClientIpKey.from("203.0.113.5")).contains(new ClientIpKey("203.0.113.5"));
        assertThat(ClientIpKey.from("2001:db8:1:2::1"))
                .isEqualTo(ClientIpKey.from("2001:db8:1:2:ffff:eeee:dddd:cccc"))
                .contains(new ClientIpKey("v6/64:2001:0db8:0001:0002"));
        assertThat(ClientIpKey.from("2001:db8:1:3::1")).isNotEqualTo(ClientIpKey.from("2001:db8:1:2::1"));
    }
}
