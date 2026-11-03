package com.hapbang.member.in;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberWithdrawalUseCase;
import com.hapbang.member.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 꼬인 탈퇴 작업 정리 (#593). 기본 5분마다. 탈퇴가 서비스 문제로 중간에 멈춘 뒤 다시 누르지 않고 떠난 회원의 지갑이
 * 닫힌 채 남지 않게 다시 연다(다시 누른 회원은 그 자리에서 정리된다). 처리는 {@link MemberWithdrawalUseCase#recoverStuck}.
 * 스케줄링과 탈퇴 기능 스위치가 모두 켜졌을 때만 동작한다
 */
@Slf4j
@Component
@ConditionalOnSchedulingEnabled
@ConditionalOnBooleanProperty(MemberWithdrawalUseCase.ENABLED)
@RequiredArgsConstructor
class MemberWithdrawalRecoveryBatch {

    /** 한 번에 정리할 최대 작업 수 */
    static final int BATCH_SIZE = 100;

    private final MemberWithdrawalUseCase memberWithdrawalUseCase;

    @Scheduled(cron = "${member.batch.withdrawal-recovery-cron:0 */5 * * * *}", zone = "Asia/Seoul")
    void run() {
        int recovered = memberWithdrawalUseCase.recoverStuck(BATCH_SIZE);
        if (recovered > 0) {
            log.info("꼬인 탈퇴 작업 {}건을 정리했습니다.", recovered);
        }
    }
}
