package com.hapbang.member.in;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberFacade;

import lombok.RequiredArgsConstructor;

/** 로컬 로그인이 켜진 환경(local·dev)에서 앱 시작 시 역할별 테스트 계정 생성 (역할마다 2개). 이미 있으면 건너뛴다 */
@Component
@Order(1)
@ConditionalOnProperty(name = "hapbang.local-login.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MemberDataInit implements ApplicationRunner {

    private final MemberFacade memberFacade;

    @Override
    public void run(ApplicationArguments args) {
        memberFacade.createTestAccounts();
    }
}
