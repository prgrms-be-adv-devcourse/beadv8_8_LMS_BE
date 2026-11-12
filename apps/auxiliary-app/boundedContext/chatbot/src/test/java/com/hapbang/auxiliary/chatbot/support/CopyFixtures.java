package com.hapbang.auxiliary.chatbot.support;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.hapbang.shared.lecture.domain.LecturePurchaseResult;
import com.hapbang.shared.lecture.dto.CartItemSyncDto;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

/** 회원·강의 원본 DTO를 짧게 만든다 (#717) */
public final class CopyFixtures {

    public static final OffsetDateTime ORDERED_AT = OffsetDateTime.of(2026, 11, 8, 10, 0, 0, 0, ZoneOffset.ofHours(9));

    private CopyFixtures() {
    }

    public static UserDto member(long id, Role role, UserStatus status, Long version) {
        return new UserDto(id, "회원" + id, role, status, version, List.of("GAME"), "게임 방송을 합니다.");
    }

    public static UserDto member(long id, Long version) {
        return member(id, Role.INFLUENCER, UserStatus.ACTIVE, version);
    }

    public static LectureSyncDto lecture(long id, long version) {
        return new LectureSyncDto(id, 900L, "강의" + id, "소개", 10_000L, true, false,
                List.of(new LectureContentSyncDto(2L, "둘째", 2), new LectureContentSyncDto(1L, "첫째", 1)), version);
    }

    public static LecturePurchaseSyncDto purchase(long orderItemId, long buyerUserId, LecturePurchaseResult result,
            long version) {
        return new LecturePurchaseSyncDto(orderItemId, buyerUserId, 100L, result, ORDERED_AT,
                result == LecturePurchaseResult.OWNED ? ORDERED_AT.plusMinutes(1) : null, version);
    }

    public static CartItemSyncDto cartItem(long id, long userId, boolean deleted, long version) {
        return new CartItemSyncDto(id, userId, 100L, deleted, ORDERED_AT, version);
    }

    public static LectureReviewSyncDto lectureReview(long id, long reviewerUserId, int rating, long version) {
        return new LectureReviewSyncDto(id, 100L, reviewerUserId, rating, true, false, version);
    }
}
