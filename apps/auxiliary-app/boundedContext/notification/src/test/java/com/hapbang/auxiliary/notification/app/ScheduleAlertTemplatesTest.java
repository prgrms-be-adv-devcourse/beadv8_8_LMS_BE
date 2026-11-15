package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.notification.domain.ScheduleKind;

/** 예약 5종 문구: 기한 시각을 적는다(알림 종류 0.13, ND-140) */
class ScheduleAlertTemplatesTest {

    @Test
    void 예약_5종마다_같은_이름의_문구가_있다() {
        assertThat(Arrays.stream(ScheduleAlertTemplates.values()).map(ScheduleAlertTemplates::kind))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ScheduleKind.values()).map(ScheduleKind::kind)
                        .toList());
    }

    @Test
    void 출연_4종은_기한_시각을_M월_d일_HH_mm으로_적는다() {
        Map<String, Object> payload = Map.of("v", 1, "title", "합방", "deadline", "2026-11-08T09:05");

        assertThat(ScheduleAlertTemplates.APPEARANCE_REQUEST_EXPIRING.render(payload))
                .isEqualTo("'합방' 출연 요청의 수락 기한은 11월 8일 09:05입니다.");
        assertThat(ScheduleAlertTemplates.APPEARANCE_RECRUIT_CLOSING.render(payload))
                .isEqualTo("'합방' 모집이 11월 8일 09:05에 마감됩니다.");
        assertThat(ScheduleAlertTemplates.APPEARANCE_STARTING.render(payload))
                .isEqualTo("'합방' 방송이 11월 8일 09:05에 시작됩니다.");
        assertThat(ScheduleAlertTemplates.APPEARANCE_CONFIRM_PERIOD_ENDING.render(payload))
                .isEqualTo("'합방' 출연을 확인해 주세요. 11월 8일 09:05에 자동으로 확인됩니다.");
    }

    @Test
    void 구매_취소_마감_임박은_구매_취소할_수_있는_마지막_날을_적는다() {
        assertThat(ScheduleAlertTemplates.LECTURE_ORDER_ITEM_CONFIRMING.render(
                Map.of("v", 1, "title", "자바 입문", "cancelUntil", "2026-11-20")))
                .isEqualTo("'자바 입문' 구매 취소는 11월 20일까지 할 수 있습니다.");
    }
}
