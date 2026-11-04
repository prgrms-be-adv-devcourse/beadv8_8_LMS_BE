package com.hapbang.member.in;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberFacade;

import lombok.RequiredArgsConstructor;

/**
 * 앱 시작 시 마이그레이션이 바꾼 중복 닉네임을 다른 앱에 알린다 (#646). 알릴 것이 없으면 아무것도 하지 않는다.
 * 실패하면 앱이 뜨지 않게 그대로 던진다(알림 표시가 남지 않아 다음 시작에 다시 한다)
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class MemberNicknameDedupNotifier implements ApplicationRunner {

    private final MemberFacade memberFacade;

    @Override
    public void run(ApplicationArguments args) {
        memberFacade.notifyNicknameDedup();
    }
}
