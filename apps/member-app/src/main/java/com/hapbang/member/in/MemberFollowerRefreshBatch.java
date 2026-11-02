package com.hapbang.member.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.member.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.member.app.MemberFacade;

import lombok.RequiredArgsConstructor;

/** 채널 팔로워 수 일일 갱신. 기본 매일 10:00 KST (EC2 운영 시간 안). 처리는 {@link MemberFacade} (#302) */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class MemberFollowerRefreshBatch {

    private final MemberFacade memberFacade;

    @Scheduled(cron = "${member.batch.follower-refresh-cron:0 0 10 * * *}", zone = "Asia/Seoul")
    void run() {
        memberFacade.refreshFollowerCounts();
    }
}
