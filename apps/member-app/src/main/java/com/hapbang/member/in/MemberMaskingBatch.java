package com.hapbang.member.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberMaskingUseCase;
import com.hapbang.member.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 탈퇴 회원 개인정보 마스킹 배치 (#594). 하루 한 번 복구 기한이 지난 탈퇴 회원을 마스킹한다. 처리는 {@link MemberMaskingUseCase}.
 * <p>
 * 운영 EC2는 09:00~18:00에만 켜져 있어(docs/deploy/README.md) 그 안인 10:30 KST에 돈다. 기한이 지나고 하루 안에 처리되면 충분하다.
 * {@link #BATCH_SIZE}명씩 끝까지 처리하고, 한 묶음에서 실패가 있으면(마스킹한 수가 묶음보다 적으면) 그날은 멈추고 다음 날 다시 한다.
 * 스케줄링이 켜졌을 때만 동작한다
 */
@Slf4j
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class MemberMaskingBatch {

    /** 한 번에 마스킹할 최대 회원 수 */
    static final int BATCH_SIZE = 100;

    private final MemberMaskingUseCase memberMaskingUseCase;

    @Scheduled(cron = "${member.batch.masking-cron:0 30 10 * * *}", zone = "Asia/Seoul")
    void run() {
        int total = 0;
        int masked;
        do {
            masked = memberMaskingUseCase.maskDue(BATCH_SIZE);
            total += masked;
        } while (masked == BATCH_SIZE);
        if (total > 0) {
            log.info("복구 기한이 지난 탈퇴 회원 {}명을 마스킹했습니다.", total);
        }
    }
}
