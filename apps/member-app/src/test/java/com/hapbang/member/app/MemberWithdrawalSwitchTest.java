package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.hapbang.member.MemberIntegrationTest;

/** 탈퇴 기능 스위치 (#593). 기본은 꺼져 있어 출연·강의·지갑 연결 코드가 없어도 앱이 뜨고, 오케스트레이터를 만들지 않는다 */
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
