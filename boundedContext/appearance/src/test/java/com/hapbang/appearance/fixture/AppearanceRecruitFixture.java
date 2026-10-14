package com.hapbang.appearance.fixture;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceRecruitContent;

/**
 * 테스트용 출연 모집글 내용. 기준 시각(now)에서 모집 마감 +2일, 방송 예정 일시 +7일, 1인당 30,000 HP × 3명으로 만든다.
 */
public final class AppearanceRecruitFixture {

    public static final long FEE_PER_PERSON = 30_000L;
    public static final int RECRUIT_COUNT = 3;
    public static final long TOTAL_FEE = FEE_PER_PERSON * RECRUIT_COUNT;

    private AppearanceRecruitFixture() {
    }

    public static AppearanceRecruitContent content(LocalDateTime now) {
        return content(now, FEE_PER_PERSON, RECRUIT_COUNT);
    }

    public static AppearanceRecruitContent content(LocalDateTime now, long feePerPerson, int recruitCount) {
        return content(now.plusDays(2), now.plusDays(7), feePerPerson, recruitCount);
    }

    public static AppearanceRecruitContent content(LocalDateTime recruitDeadline, LocalDateTime scheduledAt,
                                                   long feePerPerson, int recruitCount) {
        return new AppearanceRecruitContent("주말 게임 합방 게스트 모집", "https://www.youtube.com/@host", scheduledAt,
                60, "같이 신작 게임을 플레이할 게스트를 모집합니다.", feePerPerson, recruitCount, recruitDeadline);
    }
}
