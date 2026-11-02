package com.hapbang.appearance.domain;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 출연 리뷰 정책 (#512). 값은 {@code appearance.yml}의 {@code appearance.review}에 있고, 설정 객체
 * ({@code config.AppearanceReviewProperties})가 읽어 이 객체로 바꿔 등록한다. 이 클래스는 Spring을 모른다.
 *
 * @param writePeriod 출연 확인 시각부터 리뷰를 쓸 수 있는 기간. null이면 무기한 (지금 운영 값)
 */
public record AppearanceReviewPolicy(Duration writePeriod) {

    public AppearanceReviewPolicy {
        if (writePeriod != null && (writePeriod.isNegative() || writePeriod.isZero())) {
            throw new IllegalArgumentException("리뷰 작성 기간은 비우거나(무기한) 0보다 길어야 합니다.");
        }
    }

    /** 작성 기한이 없는 정책 */
    public static AppearanceReviewPolicy unlimited() {
        return new AppearanceReviewPolicy(null);
    }

    public boolean hasWritePeriod() {
        return writePeriod != null;
    }

    /**
     * 출연 확인 시각 기준으로 지금 리뷰를 쓸 수 있는지. 기한이 있으면 확인 시각 + 기간 전까지만 쓸 수 있다.
     */
    public boolean isWritable(LocalDateTime confirmedAt, LocalDateTime now) {
        if (writePeriod == null) {
            return true;
        }
        return now.isBefore(confirmedAt.plus(writePeriod));
    }
}
