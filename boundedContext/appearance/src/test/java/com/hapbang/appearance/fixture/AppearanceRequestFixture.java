package com.hapbang.appearance.fixture;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceRequestContent;

/**
 * 테스트용 출연 요청서 내용. 기준 시각(now)에서 수락 기한 +2일, 방송 예정 일시 +7일로 만든다.
 */
public final class AppearanceRequestFixture {

    public static final long FEE = 30_000L;

    private AppearanceRequestFixture() {
    }

    public static AppearanceRequestContent content(LocalDateTime now) {
        return content(now, FEE);
    }

    public static AppearanceRequestContent content(LocalDateTime now, long fee) {
        return content(now.plusDays(2), now.plusDays(7), fee);
    }

    public static AppearanceRequestContent content(LocalDateTime acceptDeadline, LocalDateTime scheduledAt, long fee) {
        return new AppearanceRequestContent("주말 게임 합방 출연 요청", "https://www.youtube.com/@host", scheduledAt, 60,
                "같이 신작 게임을 플레이하는 콘텐츠입니다.", fee, acceptDeadline);
    }
}
