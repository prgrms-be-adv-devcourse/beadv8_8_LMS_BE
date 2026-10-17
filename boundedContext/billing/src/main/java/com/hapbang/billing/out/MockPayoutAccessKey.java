package com.hapbang.billing.out;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

// 같은 애플리케이션 안의 가상 송금 HTTP Client만 Mock 은행 API를 부를 수 있게 하는 접근 키 (#288).
// 애플리케이션이 뜰 때마다 무작위로 만들고 설정·로그·Git에 두지 않는다. Client가 X-Mock-Payout-Key 헤더로 보내고 Controller가 비교한다.
// (Mock API는 세션 없이 호출되므로 보안 필터가 경로를 열어 두고, 이 키로 외부 호출을 막는다)
@Component
public class MockPayoutAccessKey {

    public static final String HEADER = "X-Mock-Payout-Key";

    private final String value;

    public MockPayoutAccessKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        this.value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String value() {
        return value;
    }

    // 상수 시간 비교
    public boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "MockPayoutAccessKey[****]";
    }
}
