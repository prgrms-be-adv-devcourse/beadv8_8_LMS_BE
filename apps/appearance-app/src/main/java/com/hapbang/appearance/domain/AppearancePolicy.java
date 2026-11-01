package com.hapbang.appearance.domain;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 출연 정책 수치. 값은 {@code appearance.yml}의 {@code appearance.policy}에 있고,
 * 설정 객체({@code config.AppearancePolicyProperties})가 읽어 이 객체로 바꿔 등록한다. 이 클래스는 Spring을 모른다.
 * <p>
 * 정책 수치는 코드에 직접 쓰지 않고 반드시 이 클래스를 거친다(AGENTS.md 5장).
 *
 * @param minimumFee            최저 출연료 (1인당, HP)
 * @param penaltyRatePercent    호스트 일방 취소 위약금 비율 (%)
 * @param confirmPeriod         방송 예정 일시부터 출연 확인을 받을 수 있는 기간. 지나면 자동 확인
 * @param maxCancelRequestCount 출연 1건당 출연 취소 요청 최대 횟수
 * @param maxRecruitCount       출연 모집글 하나의 최대 모집 인원 (최소는 1명)
 */
public record AppearancePolicy(
        long minimumFee,
        int penaltyRatePercent,
        Duration confirmPeriod,
        int maxCancelRequestCount,
        int maxRecruitCount) {

    private static final int PERCENT = 100;

    /** 최소 모집 인원. 모집은 게스트가 먼저 지원하는 방식이라 1명 모집도 허용한다 */
    public static final int MIN_RECRUIT_COUNT = 1;

    public AppearancePolicy {
        if (minimumFee <= 0) {
            throw new IllegalArgumentException("최저 출연료는 0보다 커야 합니다.");
        }
        // 0%면 위약금이 0원이 되어 위약금 정산 작업을 만들 수 없다 (HpTask.penalty는 1 이상만 받는다)
        if (penaltyRatePercent < 1 || penaltyRatePercent > PERCENT) {
            throw new IllegalArgumentException("위약금 비율은 1~100 사이여야 합니다.");
        }
        if (confirmPeriod == null || confirmPeriod.isNegative() || confirmPeriod.isZero()) {
            throw new IllegalArgumentException("출연 확인 기간은 0보다 길어야 합니다.");
        }
        if (maxCancelRequestCount < 1) {
            throw new IllegalArgumentException("출연 취소 요청 최대 횟수는 1 이상이어야 합니다.");
        }
        if (maxRecruitCount < MIN_RECRUIT_COUNT) {
            throw new IllegalArgumentException("최대 모집 인원은 1 이상이어야 합니다.");
        }
    }

    public boolean isBelowMinimumFee(long fee) {
        return fee < minimumFee;
    }

    /**
     * 위약금 = 출연료 × 비율. 원 단위 미만 끝전은 버린다.
     */
    public long calculatePenalty(long fee) {
        if (fee < 0) {
            throw new IllegalArgumentException("출연료는 음수일 수 없습니다.");
        }
        return Math.multiplyExact(fee, penaltyRatePercent) / PERCENT;
    }

    /**
     * 출연 확인 마감 시각. 이 시각이 지나면 자동 확인(지급)한다.
     */
    public LocalDateTime confirmDeadline(LocalDateTime scheduledAt) {
        return scheduledAt.plus(confirmPeriod);
    }

    /** 모집 인원이 1명 이상, 최대 모집 인원 이하인지 */
    public boolean isRecruitCountAllowed(int recruitCount) {
        return recruitCount >= MIN_RECRUIT_COUNT && recruitCount <= maxRecruitCount;
    }
}
