package com.hapbang.member.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberMaskingUseCase;
import com.hapbang.member.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 탈퇴 회원 개인정보 마스킹 배치 (#594). 하루 한 번 복구 기한이 지난 탈퇴 회원을 모두 마스킹한다. 처리는 {@link MemberMaskingUseCase}.
 * <p>
 * 운영 EC2는 09:00~18:00에만 켜져 있어(docs/deploy/README.md) 그 안인 10:30 KST에 돈다. 기한이 지나고 하루 안에 처리되면 충분하다.
 * 후보를 {@link #PAGE_SIZE}명씩 읽고, 실패한 회원은 건너뛰어 다음 날 다시 한다. 스케줄링이 켜졌을 때만 동작한다
 */
@Slf4j
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class MemberMaskingBatch {

    /** 한 번에 읽는 후보 수 */
    static final int PAGE_SIZE = 100;

    private final MemberMaskingUseCase memberMaskingUseCase;

    @Scheduled(cron = "${member.batch.masking-cron:0 30 10 * * *}", zone = "Asia/Seoul")
    void run() {
        int masked = memberMaskingUseCase.maskAllDue(PAGE_SIZE);
        if (masked > 0) {
            log.info("복구 기한이 지난 탈퇴 회원 {}명을 마스킹했습니다.", masked);
        }
    }
}
