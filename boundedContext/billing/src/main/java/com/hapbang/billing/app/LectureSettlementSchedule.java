package com.hapbang.billing.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

// 강의 판매 수익의 지급 가능 시각을 KST 달력일로 계산한다(#142): 구매일 다음 날을 1일 차로 30일 차가 끝날 때까지 보류하고,
// 구매일(KST) + 31일 00:00 KST부터 지급할 수 있다. 예) 2026-10-13 15:00 KST 구매 → 2026-11-13 00:00 KST부터 지급.
// UTC 날짜나 시스템 기본 시간대가 아니라 Asia/Seoul 날짜로 센다. 구매 시각을 입력으로 받으므로 현재 시각(Clock)에 의존하지 않는다.
final class LectureSettlementSchedule {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 구매 다음 날부터 보호하는 일수
    static final int PROTECTION_DAYS = 30;

    private LectureSettlementSchedule() {
    }

    // 구매 시각의 KST 날짜 + (보호 30일 + 1)일의 00:00 KST를 Instant로 돌려준다.
    static Instant payableAt(Instant purchasedAt) {
        LocalDate purchaseDate = purchasedAt.atZone(KST).toLocalDate();
        return purchaseDate.plusDays(PROTECTION_DAYS + 1L).atStartOfDay(KST).toInstant();
    }
}
