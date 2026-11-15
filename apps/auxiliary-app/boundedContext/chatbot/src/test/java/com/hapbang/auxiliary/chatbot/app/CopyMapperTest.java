package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.chatbot.domain.AppearanceCopy;
import com.hapbang.auxiliary.chatbot.domain.AppearanceReviewCopy;
import com.hapbang.auxiliary.chatbot.domain.LectureCopies;
import com.hapbang.auxiliary.chatbot.domain.MemberCopy;
import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.lecture.domain.LecturePurchaseResult;
import com.hapbang.shared.lecture.dto.CartItemSyncDto;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 받은 DTO → 사본 값 (#716, #717, #835). 해시 값을 고정해 둔다.
 * 해시가 바뀌면 다음 싱크가 모든 사본을 "고침"으로 세고 다시 쓰므로, 계산 위치를 옮겨도 값은 같아야 한다.
 */
class CopyMapperTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 20, 20, 0, 0, 123_456_789);
    private static final OffsetDateTime AT_KST = OffsetDateTime.of(AT, ZoneOffset.ofHours(9));

    @Test
    @DisplayName("출연 사본의 해시")
    void appearanceHash() {
        AppearanceCopy copy = AppearanceCopy.from(new AppearanceSyncDto(1L, 2L, 3L, AppearanceResult.SCHEDULED, AT, 4L));

        assertThat(copy.hash()).isEqualTo("9a7c043e67b5d6949643834608e77431e2ca2a868f5657c35a24d6065e6f167d");
    }

    @Test
    @DisplayName("출연 리뷰 사본의 해시")
    void appearanceReviewHash() {
        AppearanceReviewCopy copy = AppearanceReviewCopy.from(new AppearanceReviewSyncDto(10L, 1L, 2L, 5));

        assertThat(copy.hash()).isEqualTo("28b02e3c27e6424125f9f72707754422b686c6c624e5bfa0f438c08123548a37");
    }

    @Test
    @DisplayName("회원 사본의 해시")
    void memberHash() {
        MemberCopy copy = MemberCopy.from(new UserDto(7L, "닉네임", Role.INFLUENCER, UserStatus.ACTIVE, 3L,
                List.of("GAME", "MUSIC"), "소개"));

        assertThat(copy.hash()).isEqualTo("f14c082a6872db2d594b655ecaa8b03597e8ef35a8c1ab4d5478ff3c654b99e0");
    }

    @Test
    @DisplayName("강의 사본의 해시. 콘텐츠는 sequence 순으로 넣는다")
    void lectureHash() {
        LectureCopies.Lecture copy = LectureCopies.Lecture.from(new LectureSyncDto(5L, 7L, "강의", "설명", 1000L, true,
                false, List.of(new LectureContentSyncDto(12L, "둘째", 2), new LectureContentSyncDto(11L, "첫째", 1)), 9L));

        assertThat(copy.contentTitles()).containsExactly("첫째", "둘째");
        assertThat(copy.hash()).isEqualTo("4987fe1f39af1104c581945b16e6ff056a59398e3e63f81458106b9a292435b2");
    }

    @Test
    @DisplayName("구매 사본의 해시")
    void purchaseHash() {
        LectureCopies.Purchase copy = LectureCopies.Purchase.from(new LecturePurchaseSyncDto(20L, 8L, 5L,
                LecturePurchaseResult.OWNED, AT_KST, AT_KST.plusDays(1), 2L));

        assertThat(copy.hash()).isEqualTo("cfbdaa7a2639907a0be34faa587f63eef69f47431ba095f39f2d87e1b8ea5edf");
    }

    @Test
    @DisplayName("장바구니 사본의 해시")
    void cartItemHash() {
        LectureCopies.CartItem copy = LectureCopies.CartItem.from(new CartItemSyncDto(30L, 8L, 5L, false, AT_KST, 1L));

        assertThat(copy.hash()).isEqualTo("a14482acfc71af68b6545d414567bdfba09550f35b6cde62975c811eefdcbc10");
    }

    @Test
    @DisplayName("강의 리뷰 사본의 해시")
    void lectureReviewHash() {
        LectureCopies.Review copy = LectureCopies.Review.from(new LectureReviewSyncDto(40L, 5L, 8L, 4, true, false, 1L));

        assertThat(copy.hash()).isEqualTo("f9ddbbcfd02193e16ac5fcb20044e0258292535b2f983916a1ca32a09cd97b51");
    }
}
