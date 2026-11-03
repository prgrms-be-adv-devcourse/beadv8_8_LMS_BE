package com.hapbang.chat.app;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.chat.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

// 모집용 단체방의 정리 배치(설계 3.6 "1단계 구현 범위와 세부"). 처리는 ChatGroupHistoryExpiryService·ChatGroupInviteRequestCleaner가 한다.
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class ChatGroupRoomBatch {

    private final ChatGroupHistoryExpiryService chatGroupHistoryExpiryService;
    private final ChatGroupInviteRequestCleaner chatGroupInviteRequestCleaner;

    // 조회 만료 상태 기록. 주기 기본 10m은 미확정 초기값이다(노출 차단은 조회 때 하므로 늦어도 된다). 기본값은 코드가 원본이고 같은 키의 yaml·환경 변수가 덮는다
    @Scheduled(initialDelayString = "${hapbang.chat.group.history-expiry-interval:10m}",
            fixedDelayString = "${hapbang.chat.group.history-expiry-interval:10m}")
    void expireHistory() {
        chatGroupHistoryExpiryService.expireDue(LocalDateTime.now());
    }

    // 보존 기간이 지난 초대 결과 기록 정리. 주기 기본 1h은 미확정 초기값이다(보존 기간 24h보다 충분히 짧다)
    @Scheduled(initialDelayString = "${hapbang.chat.group.invite-request-cleanup-interval:1h}",
            fixedDelayString = "${hapbang.chat.group.invite-request-cleanup-interval:1h}")
    void cleanUpInviteRequests() {
        chatGroupInviteRequestCleaner.deleteExpired(LocalDateTime.now());
    }
}
