package com.hapbang.appearance.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.appearance.app.AppearanceAutoConfirmUseCase;
import com.hapbang.appearance.app.AppearanceCancelAutoRejectUseCase;
import com.hapbang.appearance.app.AppearanceHoldRecheckUseCase;
import com.hapbang.appearance.app.AppearanceEventRepublishUseCase;
import com.hapbang.appearance.app.AppearanceRecruitDeadlineCloseUseCase;
import com.hapbang.appearance.app.AppearanceRequestExpireUseCase;
import com.hapbang.appearance.app.HpTaskRetryUseCase;
import com.hapbang.appearance.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 출연 배치. 실행 주기만 정하고 처리는 UseCase가 한다 (팀 공통 스케줄링 규칙 #157).
 * <ul>
 *     <li>{@code hapbang.scheduling.enabled=true}일 때만 빈이 생긴다 (앱에서만 켜짐, 테스트는 UseCase를 직접 호출).</li>
 *     <li>배치는 로그인 회원이 아니라 시스템이 실행하므로 역할 검사({@code @PreAuthorize})가 있는 Facade를 거치지 않고
 *     UseCase를 직접 부른다 (ISSUE.md APR-26).</li>
 *     <li>매분 초를 엇갈려 실행한다: 보류 재확인(00초) → 알림 Kafka 재발행(05초) → 모집 기한 마감(10초) → 수락 기한 만료(20초)
 *     → 출연 취소 요청 자동 거절(30초) → HP 작업 재시도(40초) → 출연 자동 확인(50초).
 *     같은 배치는 이전 실행이 끝나야 다음 실행이 시작된다.</li>
 * </ul>
 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class AppearanceBatch {

    private final AppearanceHoldRecheckUseCase appearanceHoldRecheckUseCase;
    private final AppearanceRecruitDeadlineCloseUseCase appearanceRecruitDeadlineCloseUseCase;
    private final AppearanceRequestExpireUseCase appearanceRequestExpireUseCase;
    private final AppearanceCancelAutoRejectUseCase appearanceCancelAutoRejectUseCase;
    private final HpTaskRetryUseCase hpTaskRetryUseCase;
    private final AppearanceAutoConfirmUseCase appearanceAutoConfirmUseCase;
    private final AppearanceEventRepublishUseCase appearanceEventRepublishUseCase;

    @Scheduled(cron = "${appearance.batch.hold-recheck-cron:0 * * * * *}", zone = "Asia/Seoul")
    void recheckHolds() {
        appearanceHoldRecheckUseCase.recheckDue();
    }

    @Scheduled(cron = "${appearance.batch.event-republish-cron:5 * * * * *}", zone = "Asia/Seoul")
    void republishEvents() {
        appearanceEventRepublishUseCase.republishDue();
    }

    @Scheduled(cron = "${appearance.batch.recruit-close-cron:10 * * * * *}", zone = "Asia/Seoul")
    void closeRecruits() {
        appearanceRecruitDeadlineCloseUseCase.closeDue();
    }

    @Scheduled(cron = "${appearance.batch.expire-cron:20 * * * * *}", zone = "Asia/Seoul")
    void expireRequests() {
        appearanceRequestExpireUseCase.expireDue();
    }

    @Scheduled(cron = "${appearance.batch.cancel-auto-reject-cron:30 * * * * *}", zone = "Asia/Seoul")
    void autoRejectCancelRequests() {
        appearanceCancelAutoRejectUseCase.rejectDue();
    }

    @Scheduled(cron = "${appearance.batch.hp-task-retry-cron:40 * * * * *}", zone = "Asia/Seoul")
    void retryHpTasks() {
        hpTaskRetryUseCase.retryDue();
    }

    @Scheduled(cron = "${appearance.batch.auto-confirm-cron:50 * * * * *}", zone = "Asia/Seoul")
    void autoConfirmAppearances() {
        appearanceAutoConfirmUseCase.confirmDue();
    }
}
