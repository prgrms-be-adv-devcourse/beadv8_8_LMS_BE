package com.hapbang.member.in;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberFacade;

import lombok.RequiredArgsConstructor;

/** 로컬 앱 시작 시 역할별 테스트 계정 생성 (역할마다 2개). 다른 모듈 DataInit보다 먼저 실행된다 */
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
