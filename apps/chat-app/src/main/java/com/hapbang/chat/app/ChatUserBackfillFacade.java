package com.hapbang.chat.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatUserBackfillResult;

import lombok.RequiredArgsConstructor;

/**
 * 관리자가 수동으로 실행하는 기존 회원 백필 진입점(설계 문서 5.4). 자동 실행(Scheduler·ApplicationRunner)은 두지 않는다.
 * 관리자 API는 두 겹으로 막는다: global SecurityConfig의 {@code /api/v1/*}{@code /admin/**}(ADMIN)과 이 Facade의 {@code @PreAuthorize}.
 * 회원 내부 API 호출이 있어 트랜잭션을 열지 않는다(회원마다 {@link ChatUserSyncService}가 짧은 트랜잭션을 연다).
 */
@Component
@RequiredArgsConstructor
public class ChatUserBackfillFacade {

    private final ChatUserBackfillService chatUserBackfillService;

    @PreAuthorize(ChatRoles.ADMIN)
    public ChatUserBackfillResult backfill(long afterId, int limit) {
        return chatUserBackfillService.backfill(afterId, limit);
    }
}
