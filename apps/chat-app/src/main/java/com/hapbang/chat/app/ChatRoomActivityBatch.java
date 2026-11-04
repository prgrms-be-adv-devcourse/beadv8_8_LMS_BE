package com.hapbang.chat.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.chat.global.config.ConditionalOnChatRealtime;
import com.hapbang.chat.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

// 새 메시지 알림 묶음을 주기마다 보낸다(설계 Q15). 처리는 ChatRoomActivityNotifier가 한다.
@Component
@ConditionalOnChatRealtime
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class ChatRoomActivityBatch {

    private final ChatRoomActivityNotifier chatRoomActivityNotifier;

    // 묶음 주기 기본 1s는 미확정 초기값(Q12)이다. 기본값은 코드가 원본이고 같은 키의 yaml·환경 변수가 덮는다
    @Scheduled(fixedDelayString = "${hapbang.chat.realtime.activity.batch-interval:1s}")
    void run() {
        chatRoomActivityNotifier.flush();
    }
}
