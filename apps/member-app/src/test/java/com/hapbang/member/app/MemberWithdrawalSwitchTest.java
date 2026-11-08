package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.hapbang.member.MemberIntegrationTest;

/**
 * 탈퇴 기능 스위치 (#593). 꺼져 있으면 출연·강의·지갑 Client 주소와 연결 코드가 없어도 앱이 뜨고, 오케스트레이터를 만들지 않는다.
 * 테스트 설정은 운영 application.yaml을 읽지 않아 꺼진 상태다(운영 기본값은 켬, #654). 켰을 때의 배선은 {@link MemberWithdrawalWiringTest}
 */
@MemberIntegrationTest
class MemberWithdrawalSwitchTest {

    @Autowired
    ApplicationContext applicationContext;

    @Test
    @DisplayName("스위치가 꺼져 있으면 탈퇴 오케스트레이터가 없다")
    void disabledByDefault() {
        assertThat(applicationContext.getBeanProvider(MemberWithdrawalUseCase.class).getIfAvailable()).isNull();
    }
}
