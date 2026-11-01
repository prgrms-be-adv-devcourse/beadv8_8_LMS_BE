package com.hapbang.chat.app;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.chat.global.config.ConditionalOnChatRealtime;
import com.hapbang.chat.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 이 앱의 연결 전부의 접속 정보 만료를 늘리고, 만료가 지난 접속 정보를 지운다(설계 Q21).
@Slf4j
@Component
@ConditionalOnChatRealtime
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class ChatPresenceHeartbeat {

    private final ChatPresence chatPresence;
    private final LocalChatConnections localChatConnections;

    // 하트비트 주기 기본 30s는 미확정 초기값(Q12)이다. 만료(presence.ttl)보다 충분히 짧게 둔다
    @Scheduled(fixedDelayString = "${hapbang.chat.realtime.presence.heartbeat-interval:30s}")
    void run() {
        Map<Long, Set<String>> sessions = new HashMap<>();
        for (Long userId : localChatConnections.connectedUserIds()) {
            Set<String> ids = localChatConnections.sessionIdsOf(userId);
            if (!ids.isEmpty()) {
                sessions.put(userId, ids);
            }
        }
        try {
            chatPresence.heartbeat(sessions);
            chatPresence.purgeExpired();
        } catch (RuntimeException exception) {
            // 다음 주기에 다시 한다. 그 사이 접속 정보가 만료되면 알림을 놓치고 FE 주기 대조가 메운다
            log.warn("접속 정보 갱신 실패 users={}", sessions.size(), exception);
        }
    }
}
