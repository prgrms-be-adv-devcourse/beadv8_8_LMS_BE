package com.hapbang.billing.out;

import java.util.Arrays;
import java.util.Set;

import org.springframework.core.env.Environment;

// 가상 송금(Mock 은행)을 켤지 정한다 (#288). 다음을 모두 만족할 때만 켠다:
//   enabled=true AND 활성 프로필이 하나 이상 AND 활성 프로필이 전부 local 또는 test
// - 실제로 활성화된 프로필(getActiveProfiles)만 본다. 프로필을 지정하지 않은 상태나 Spring 기본 프로필(spring.profiles.default=local
//   포함)은 local·test로 보지 않는다 → 로컬에서 Mock을 쓰려면 SPRING_PROFILES_ACTIVE=local(또는 --spring.profiles.active=local)로 실행한다.
// - 허용 목록(local·test) 밖의 프로필이 하나라도 섞이면(staging·dev·prod 등, local,staging 포함) 꺼진다.
// 운영(prod)·dev 서버에 설정이 잘못 들어가도(환경 변수 HAPBANG_BILLING_MOCK_PAYOUT_ENABLED=true 등) Mock API·시나리오 제어·
// 전용 보안 체인 빈이 생기지 않고, 송금 Client도 Mock을 부르지 않는다.
public final class MockPayoutActivation {

    public static final String ENABLED_PROPERTY = "hapbang.billing.mock-payout.enabled";
    static final Set<String> ALLOWED = Set.of("local", "test");

    private MockPayoutActivation() {
    }

    public static boolean isActive(Environment environment) {
        if (!requested(environment)) {
            return false;
        }
        String[] active = environment.getActiveProfiles();
        return active.length > 0 && Arrays.stream(active).allMatch(ALLOWED::contains);
    }

    // 설정으로 켜 달라고 했는지(프로필 때문에 꺼졌는지 알리는 로그용)
    static boolean requested(Environment environment) {
        return environment.getProperty(ENABLED_PROPERTY, Boolean.class, false);
    }
}
