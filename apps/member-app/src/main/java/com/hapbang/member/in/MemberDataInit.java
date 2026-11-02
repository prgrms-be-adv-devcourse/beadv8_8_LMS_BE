package com.hapbang.member.in;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberFacade;

import lombok.RequiredArgsConstructor;

/**
 * 앱 시작 시 역할별 초기 계정(인플루언서·인증된 인플루언서·관리자)을 만든다 (#520). local·운영 프로필에서만 만들고, 이미 있으면 건너뛴다.
 * 모듈 테스트는 application.yaml을 읽지 않아 프로필이 없으므로 만들지 않는다 (테스트는 테이블을 비우며 회원 수를 확인한다)
 */
@Component
@Order(1)
@Profile({"local", "prod"})
@RequiredArgsConstructor
public class MemberDataInit implements ApplicationRunner {

    private final MemberFacade memberFacade;

    @Override
    public void run(ApplicationArguments args) {
        memberFacade.createSeedAccounts();
    }
}
