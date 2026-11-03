package com.hapbang.member.in;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberEventRepublishUseCase;
import com.hapbang.member.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 회원 이벤트 Kafka 재발행 배치 (#421). 기본 매분 5초에 실행한다(출연·강의·지갑·고객센터와 같은 주기).
 * 처리는 {@link MemberEventRepublishUseCase}가 한다. 스케줄링이 켜졌을 때만 동작한다
 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class MemberEventRepublishBatch {

    private final MemberEventRepublishUseCase memberEventRepublishUseCase;

    @Scheduled(cron = "${member.batch.event-republish-cron:5 * * * * *}", zone = "Asia/Seoul")
    void run() {
        memberEventRepublishUseCase.republishDue(Instant.now());
    }
}
