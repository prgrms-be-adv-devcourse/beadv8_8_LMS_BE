package com.hapbang.chat.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.chat.global.config.ConditionalOnChatRealtime;
import com.hapbang.chat.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

// 안 읽은 사람 수 갱신 묶음을 주기마다 보낸다(설계 Q36). 처리는 ChatReadCountNotifier가 한다.
@Component
@ConditionalOnChatRealtime
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class ChatReadCountBatch {

    private final ChatReadCountNotifier chatReadCountNotifier;

    // 묶음 주기 기본 1s는 Q36의 "방별 최대 1초"이며 미확정 운영 수치(Q12)다. 기본값은 코드가 원본이고 같은 키의 yaml·환경 변수가 덮는다
    @Scheduled(fixedDelayString = "${hapbang.chat.realtime.read-counts.batch-interval:1s}")
    void run() {
        chatReadCountNotifier.flush();
    }
}
