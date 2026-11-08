package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.out.AppearanceWithdrawalBlockSource;
import com.hapbang.member.out.BillingWalletWithdrawalGate;
import com.hapbang.member.out.LectureWithdrawalBlockSource;
import com.hapbang.member.out.WithdrawalBlockSource;

/**
 * 탈퇴 연결 배선 (#654). {@link MemberWithdrawalSwitchTest}(꺼지면 오케스트레이터 없음)의 짝이다. 스위치를 켜고 출연·강의·지갑 Client 주소를
 * 주면, 가짜 없이 진짜 연결 코드 3개로 오케스트레이터가 뜬다. 다른 앱을 부르지는 않으므로 주소는 아무 값이나 쓴다.
 * 운영 k8s 설정에서 주소를 빠뜨리거나 연결 코드의 조건이 틀리면 연결 코드가 생기지 않아 앱이 시작하지 못하는데, 그 실수를 배포 전에 잡는다
 */
@MemberIntegrationTest
@TestPropertySource(properties = {
        MemberWithdrawalUseCase.ENABLED + "=true",
        "hapbang.clients.appearance.base-url=http://localhost:1",
        "hapbang.clients.lecture.base-url=http://localhost:1",
        "hapbang.clients.billing.base-url=http://localhost:1"
})
class MemberWithdrawalWiringTest {

    @Autowired
    MemberWithdrawalUseCase memberWithdrawalUseCase;

    @Test
    @DisplayName("스위치를 켜고 세 주소를 주면 출연 → 강의 순서의 막힌 이유 연결과 지갑 연결로 오케스트레이터가 뜬다")
    void wiresRealConnectors() {
        @SuppressWarnings("unchecked")
        List<WithdrawalBlockSource> blockSources =
                (List<WithdrawalBlockSource>) ReflectionTestUtils.getField(memberWithdrawalUseCase, "blockSources");

        assertThat(blockSources).extracting(Object::getClass)
                .containsExactly(AppearanceWithdrawalBlockSource.class, LectureWithdrawalBlockSource.class);
        assertThat(ReflectionTestUtils.getField(memberWithdrawalUseCase, "walletGate"))
                .isInstanceOf(BillingWalletWithdrawalGate.class);
    }
}
