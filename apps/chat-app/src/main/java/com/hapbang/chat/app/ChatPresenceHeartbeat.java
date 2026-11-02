package com.hapbang.chat.app;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.chat.global.config.ConditionalOnChatRealtime;
import com.hapbang.chat.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이 앱의 연결 전부의 접속 정보 만료를 늘리고, 만료가 지난 접속 정보를 지운다(설계 Q21). 같은 주기에 로컬 연결의 두 번째 확인을 한다
 * (설계 문서 6장·Q8).
 * <ul>
 *     <li>연결 만료(access token {@code exp})가 지난 연결을 닫는다. 만료 예약의 두 번째 확인이다.</li>
 *     <li>로컬 회원의 로그아웃 표식을 한 번에 읽어, 표식보다 먼저 인증한 연결을 {@code 1008 LOGGED_OUT}으로 닫는다. 제어 채널로 받은
 *     로그아웃 종료를 놓쳐도 이 주기 안에 회복한다. 표식을 읽지 못하면(Redis 실패) 이번 주기는 건너뛰고 연결 만료가 닫는다.</li>
 * </ul>
 * 세 일은 서로 실패가 번지지 않게 따로 감싼다. 닫기는 닫기 실행기에 맡기므로 이 스레드는 닫기를 기다리지 않는다.
 */
@Slf4j
@Component
@ConditionalOnChatRealtime
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class ChatPresenceHeartbeat {

    private final ChatPresence chatPresence;
    private final ChatLogoutMarkers chatLogoutMarkers;
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
        closeExpiredConnections();
        closeLoggedOutConnections(sessions.keySet());
    }

    private void closeExpiredConnections() {
        try {
            int requested = localChatConnections.closeExpired();
            if (requested > 0) {
                log.info("하트비트에서 만료가 지난 채팅 연결의 닫기를 맡김 requested={}", requested);
            }
        } catch (RuntimeException exception) {
            log.warn("하트비트의 연결 만료 확인 실패", exception);
        }
    }

    private void closeLoggedOutConnections(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, Instant> loggedOutAt;
        try {
            loggedOutAt = chatLogoutMarkers.loggedOutAt(userIds);
        } catch (RuntimeException exception) {
            // 이번 주기는 건너뛴다. 제어 채널의 로그아웃 종료와 연결 만료가 남는다
            log.warn("로그아웃 표식을 읽지 못해 이번 주기의 로그아웃 확인을 건너뜀 users={}", userIds.size(), exception);
            return;
        }
        int requested = localChatConnections.closeLoggedOutBefore(loggedOutAt);
        if (requested > 0) {
            log.info("로그아웃 표식보다 먼저 인증한 채팅 연결의 닫기를 맡김 requested={}", requested);
        }
    }
}
