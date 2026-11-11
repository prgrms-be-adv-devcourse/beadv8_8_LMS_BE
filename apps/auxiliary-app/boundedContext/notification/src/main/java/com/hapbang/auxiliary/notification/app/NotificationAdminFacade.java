package com.hapbang.auxiliary.notification.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 알림 관리자 기능(ND-079). 관리자만 쓴다. 회원이 쓰는 알림 API의 관리자 403(ND-052)과 반대 방향이다. global 보안 설정도
 * {@code /api/v1/*}{@code /admin/**}을 관리자로 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationAdminFacade {

    private final NotificationUserBackfillService backfill;

    /**
     * 회원 복제본 Backfill을 다시 실행하고 끝날 때까지 기다린다(동기). 응답이 곧 이 실행의 결과다(ND-089).
     *
     * @throws NotificationException 다른 실행이 돌고 있으면 {@code BACKFILL_RUNNING}(409), 끝까지 하지 못하면
     *                               {@code BACKFILL_FAILED}(503)
     */
    @PreAuthorize("hasRole('ADMIN')")
    public BackfillResult rerunUserBackfill() {
        try {
            return backfill.run();
        } catch (NotificationException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("관리자 재실행 Backfill을 마치지 못했다", e);
            throw new NotificationException(NotificationErrorCode.BACKFILL_FAILED);
        }
    }
}
