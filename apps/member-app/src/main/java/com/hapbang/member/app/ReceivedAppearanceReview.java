package com.hapbang.member.app;

import com.hapbang.member.domain.User;
import com.hapbang.shared.appearance.dto.AppearanceReceivedReviewDto;

/**
 * 회원이 받은 출연 리뷰 하나와 쓴 회원 (#555)
 *
 * @param review   출연 앱이 준 리뷰
 * @param reviewer 쓴 회원. 회원 DB에 없으면 null
 */
public record ReceivedAppearanceReview(AppearanceReceivedReviewDto review, User reviewer) {
}
