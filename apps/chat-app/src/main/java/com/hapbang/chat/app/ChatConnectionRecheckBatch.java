package com.hapbang.chat.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.chat.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

// 로컬 연결 회원의 주기 재검사(설계 5.3). 놓친 제어 명령·중복으로 흡수된 제어 작업을 회복한다. 처리는 ChatConnectionRecheckService가 한다.
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class ChatConnectionRecheckBatch {

    private final ChatConnectionRecheckService chatConnectionRecheckService;

    // 재검사 간격 기본 1m은 미확정 초기값(Q12)이다. 기본값은 코드가 원본이고 같은 키의 yaml·환경 변수가 덮는다
    @Scheduled(initialDelayString = "${hapbang.chat.realtime.recheck.interval:1m}",
            fixedDelayString = "${hapbang.chat.realtime.recheck.interval:1m}")
    void run() {
        chatConnectionRecheckService.recheckAllConnected();
    }
}
