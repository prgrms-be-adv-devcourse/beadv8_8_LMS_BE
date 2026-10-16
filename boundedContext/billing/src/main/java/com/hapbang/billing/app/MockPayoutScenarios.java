package com.hapbang.billing.app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.hapbang.billing.out.ConditionalOnMockPayout;
import com.hapbang.billing.out.MockPayoutProperties;
import com.hapbang.billing.out.MockPayoutScenario;

// 가상 송금 결과 시나리오 제어 (#288, Mock이 켜졌을 때만 빈이 생긴다). HTTP로 열지 않고 같은 프로세스의 테스트 코드만 쓴다.
// 지급 요청 ID마다 다음 한 번의 새 요청 결과를 정해 둘 수 있고(처음 POST가 소비), 지정하지 않으면 기본 시나리오(설정, 기본 SUCCESS)다.
// 요청 값(계좌번호 등)으로 결과를 정하는 숨은 규칙은 두지 않는다.
@Component
@ConditionalOnMockPayout
public class MockPayoutScenarios {

    private final Map<String, MockPayoutScenario> next = new ConcurrentHashMap<>();
    private final MockPayoutScenario defaultScenario;

    public MockPayoutScenarios(MockPayoutProperties properties) {
        this.defaultScenario = properties.defaultScenario();
    }

    // 이 지급 요청 ID의 다음 새 요청 결과를 정한다(테스트 전용).
    public void set(String payoutRequestId, MockPayoutScenario scenario) {
        next.put(payoutRequestId, scenario);
    }

    public void clear() {
        next.clear();
    }

    // 새 요청의 시나리오. 정해 둔 값은 한 번만 쓴다(SERVER_ERROR 뒤 같은 ID 재요청은 기본 시나리오로 처리된다).
    MockPayoutScenario take(String payoutRequestId) {
        MockPayoutScenario scenario = next.remove(payoutRequestId);
        return scenario == null ? defaultScenario : scenario;
    }
}
