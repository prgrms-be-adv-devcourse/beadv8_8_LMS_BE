package com.hapbang.lecture.app;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.hapbang.lecture.domain.LectureWithdrawalBlockCode;
import com.hapbang.lecture.out.WithdrawalBlockTotal;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

/** 주문 항목 집계와 강의 수를 탈퇴 막힌 이유로 바꾼다 (#625, D75) */
final class LectureWithdrawalBlocks {

    private LectureWithdrawalBlocks() {
    }

    /**
     * @param totals               이유별 주문 항목 집계. 건수가 0인 집계는 이유가 아니다
     * @param blockingLectureCount 삭제하지 않은 판매 중·검수 대기 강의 수
     * @param now                  조회 시각 (KST)
     */
    static WithdrawalBlocksDto of(List<WithdrawalBlockTotal> totals, long blockingLectureCount, LocalDateTime now) {
        Map<LectureWithdrawalBlockCode, WithdrawalBlockTotal> byCode = new EnumMap<>(LectureWithdrawalBlockCode.class);
        totals.stream().filter(total -> total.count() > 0).forEach(total -> byCode.put(total.code(), total));
        if (blockingLectureCount > 0) {
            byCode.put(LectureWithdrawalBlockCode.LECTURE_ON_SALE,
                    new WithdrawalBlockTotal(LectureWithdrawalBlockCode.LECTURE_ON_SALE, blockingLectureCount, null, 0));
        }
        List<WithdrawalBlockReasonDto> reasons = new ArrayList<>();
        // EnumMap은 선언 순서로 돈다
        byCode.values().forEach(total -> reasons.add(new WithdrawalBlockReasonDto(total.code().name(),
                total.code().message(total.count()), availableAt(total, now))));
        return new WithdrawalBlocksDto(reasons);
    }

    /**
     * 기다리면 풀리는 날짜. 지급이 끝나면 풀리는 이유만 있고, 가장 늦은 지급 예정 시각의 날짜다(수익 API의 지급 예정 시각과 같다).
     * 지급 예정 시각을 정할 수 없는 항목(지급 중단·지급 실패)이 있거나, 가장 늦은 지급 예정 시각이 지나 지급 처리 중이면 정하지 않는다
     */
    private static LocalDate availableAt(WithdrawalBlockTotal total, LocalDateTime now) {
        LocalDateTime latest = total.latestPayoutScheduledAt();
        if (!total.code().isReleasedByPayout() || total.undatableCount() > 0 || latest == null || !latest.isAfter(now)) {
            return null;
        }
        return latest.toLocalDate();
    }
}
