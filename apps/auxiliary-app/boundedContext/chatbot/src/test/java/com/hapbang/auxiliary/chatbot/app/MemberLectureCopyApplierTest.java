package com.hapbang.auxiliary.chatbot.app;

import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.cartItem;
import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.lecture;
import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.lectureReview;
import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.member;
import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.purchase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.domain.CopyOutcome;
import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.out.CopyWrite;
import com.hapbang.auxiliary.chatbot.out.MissingCopy;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.CopyFixtures;
import com.hapbang.shared.lecture.domain.LecturePurchaseResult;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

/** 회원·강의 사본 적용기를 실제 DB로 검증한다 (#717) */
class MemberLectureCopyApplierTest extends ChatbotIntegrationTestBase {

    @Autowired
    private CopyApplier copyApplier;

    @Test
    @DisplayName("회원: 변경 이벤트로도 사본이 없으면 만든다. 닉네임은 저장하지 않고 역할·상태·분야·자기소개를 담는다")
    void memberCreatedFromModified() {
        assertThat(copyApplier.apply(member(1, 3L)).outcome()).isEqualTo(CopyOutcome.INSERTED);

        Map<String, Object> row = rows.row("member_copy", "id", 1);
        assertThat(row).containsEntry("role", "INFLUENCER").containsEntry("status", "ACTIVE").containsEntry("version", 3L)
                .containsEntry("bio", "게임 방송을 합니다.").doesNotContainKey("nickname");
        assertThat(rows.memberCategories(1)).isEqualTo("[\"GAME\"]");
    }

    @Test
    @DisplayName("회원 version 규칙(UserDto): 우리에 version이 있으면 version 없는 정보는 무시, 없으면 만들되 version은 비워 둔다")
    void memberNullVersion() {
        UserDto withoutVersion = new UserDto(1L, "회원1", Role.USER, UserStatus.ACTIVE, null, List.of(), null);
        assertThat(copyApplier.apply(withoutVersion).outcome()).isEqualTo(CopyOutcome.INSERTED);
        assertThat(rows.row("member_copy", "id", 1)).containsEntry("version", null);

        // 우리 version이 비어 있으면 version 있는 정보로 고친다
        assertThat(copyApplier.apply(member(1, Role.INFLUENCER, UserStatus.ACTIVE, 2L)).outcome())
                .isEqualTo(CopyOutcome.SOURCE_NEWER);
        // 이제 version 없는 옛 정보는 무시한다
        UserDto stale = new UserDto(1L, "회원1", Role.USER, UserStatus.WITHDRAWN, null, List.of(), null);
        assertThat(copyApplier.apply(stale).outcome()).isEqualTo(CopyOutcome.OURS_NEWER);
        assertThat(rows.row("member_copy", "id", 1)).containsEntry("role", "INFLUENCER").containsEntry("version", 2L);
        // 작은 version도 무시하고, 닉네임만 바뀐 같은 version은 해시가 달라 고친다(값은 같다)
        assertThat(copyApplier.apply(member(1, Role.USER, UserStatus.ACTIVE, 1L)).outcome())
                .isEqualTo(CopyOutcome.OURS_NEWER);
        UserDto renamed = new UserDto(1L, "새이름", Role.INFLUENCER, UserStatus.ACTIVE, 2L, List.of("GAME"),
                "게임 방송을 합니다.");
        assertThat(copyApplier.apply(renamed).outcome()).isEqualTo(CopyOutcome.SAME_VERSION_DIFFERENT);
    }

    @Test
    @DisplayName("강의 색인 요청: 값이 바뀌거나 원본 없음 표시가 바뀔 때만 같은 트랜잭션에서 적는다. 값이 같은 반영·version만 오른 반영은 적지 않는다")
    void lectureIndexTasks() {
        copyApplier.apply(lecture(5, 1), null);
        assertThat(lectureTasks()).containsExactly("5");

        // 같은 값(이벤트 중복, 매시 싱크)과 version만 오른 값은 색인을 바꾸지 않는다
        copyApplier.apply(lecture(5, 1), null);
        copyApplier.apply(lecture(5, 1), 1L);
        assertThat(copyApplier.apply(lecture(5, 2), 1L).outcome()).isEqualTo(CopyOutcome.VERSION_ONLY);
        assertThat(lectureTasks()).hasSize(1);

        // 판매 중지
        LectureSyncDto stopped = new LectureSyncDto(5L, 900L, "강의5", "소개", false, false, lecture(5, 3).contents(), 3L);
        assertThat(copyApplier.apply(stopped, null).outcome()).isEqualTo(CopyOutcome.SOURCE_NEWER);
        assertThat(lectureTasks()).containsExactly("5", "5");

        // 원본 없음 표시, 다시 보임(값은 같아도 색인의 표시가 바뀐다)
        clock.advance(Duration.ofMinutes(1));
        assertThat(copyApplier.markMissingLectures(2L, CopyTimes.now(clockBean))).extracting(MissingCopy::sourceId)
                .containsExactly(5L);
        assertThat(lectureTasks()).containsExactly("5", "5", "5");
        CopyWrite restored = copyApplier.apply(stopped, 3L);
        assertThat(restored.outcome()).isEqualTo(CopyOutcome.UNCHANGED);
        assertThat(restored.restored()).isTrue();
        assertThat(lectureTasks()).containsExactly("5", "5", "5", "5");
        assertThat(rows.indexTasks()).allSatisfy(task -> assertThat(task).containsEntry("index_type", "LECTURE")
                .containsEntry("action", "UPSERT"));
    }

    @Test
    @DisplayName("회원 역할·상태가 비어 있으면 이벤트와 싱크 모두 같은 메시지로 거절한다")
    void memberRequiresRoleAndStatus() {
        assertThatThrownBy(() -> copyApplier.apply(new UserDto(1L, "회원1", null, UserStatus.ACTIVE, 1L, List.of(), null)))
                .isInstanceOf(NullPointerException.class).hasMessage("user.role은 필수입니다.");
        assertThatThrownBy(() -> copyApplier.apply(new UserDto(1L, "회원1", Role.USER, null, 1L, List.of(), null)))
                .isInstanceOf(NullPointerException.class).hasMessage("user.status는 필수입니다.");
        assertThat(rows.count("member_copy")).isZero();
    }

    private List<String> lectureTasks() {
        return jdbcTemplate.queryForList("SELECT doc_id FROM chatbot.index_task WHERE index_type = 'LECTURE' ORDER BY id",
                String.class);
    }

    @Test
    @DisplayName("강의: 콘텐츠는 sequence 순 제목만 담고, 강의가 판단한 판매 여부·삭제를 그대로 담는다")
    void lectureCopy() {
        assertThat(copyApplier.apply(lecture(5, 1), null).outcome()).isEqualTo(CopyOutcome.INSERTED);

        assertThat(rows.row("lecture_copy", "id", 5)).containsEntry("seller_user_id", 900L).containsEntry("on_sale", true)
                .containsEntry("deleted", false).containsEntry("version", 1L);
        assertThat(jdbcTemplate.queryForObject("SELECT content_titles::text FROM chatbot.lecture_copy WHERE id = 5",
                String.class)).isEqualTo("[\"첫째\", \"둘째\"]");
        assertThat(copyApplier.apply(lecture(5, 1), null).outcome()).isEqualTo(CopyOutcome.UNCHANGED);
        assertThat(copyApplier.apply(lecture(5, 0), null).outcome()).isEqualTo(CopyOutcome.OURS_NEWER);
    }

    @Test
    @DisplayName("구매·장바구니·강의 리뷰: 판정 규칙이 같고, 시각은 offset이 달라도 같은 KST 시각이면 해시가 같다")
    void lectureActivities() {
        assertThat(copyApplier.apply(purchase(7, 2, LecturePurchaseResult.PENDING, 0), null).outcome())
                .isEqualTo(CopyOutcome.INSERTED);
        assertThat(copyApplier.apply(purchase(7, 2, LecturePurchaseResult.OWNED, 1), null).outcome())
                .isEqualTo(CopyOutcome.SOURCE_NEWER);
        assertThat(rows.row("lecture_purchase_copy", "order_item_id", 7)).containsEntry("result", "OWNED")
                .containsEntry("ordered_at", Timestamp.valueOf(LocalDateTime.of(2026, 11, 8, 10, 0)))
                .containsEntry("purchased_at", Timestamp.valueOf(LocalDateTime.of(2026, 11, 8, 10, 1)));
        // 같은 시각을 UTC offset으로 받아도 같은 값이다
        OffsetDateTime utc = CopyFixtures.ORDERED_AT.withOffsetSameInstant(ZoneOffset.UTC);
        LecturePurchaseSyncDto sameInUtc = new LecturePurchaseSyncDto(7L, 2L, 100L, LecturePurchaseResult.OWNED, utc,
                utc.plusMinutes(1), 1);
        assertThat(copyApplier.apply(sameInUtc, null).outcome()).isEqualTo(CopyOutcome.UNCHANGED);

        assertThat(copyApplier.apply(cartItem(8, 2, false, 0), null).outcome()).isEqualTo(CopyOutcome.INSERTED);
        assertThat(copyApplier.apply(cartItem(8, 2, true, 1), null).outcome()).isEqualTo(CopyOutcome.SOURCE_NEWER);
        assertThat(rows.row("cart_item_copy", "id", 8)).containsEntry("deleted", true);

        assertThat(copyApplier.apply(lectureReview(9, 2, 4, 0), null).outcome()).isEqualTo(CopyOutcome.INSERTED);
        assertThat(copyApplier.apply(lectureReview(9, 2, 5, 0), null).outcome())
                .isEqualTo(CopyOutcome.SAME_VERSION_DIFFERENT);
        assertThat(rows.row("lecture_review_copy", "id", 9)).containsEntry("rating", 5).containsEntry("counted", true);
    }

    @Test
    @DisplayName("늦게 온 옛 이벤트는 강의 사본 행을 건드리지 않는다(#716과 같은 규칙)")
    void staleEventIgnored() {
        copyApplier.apply(cartItem(8, 2, true, 3), null);
        jdbcTemplate.update("UPDATE chatbot.cart_item_copy SET missing_in_source = TRUE WHERE id = 8");
        Map<String, Object> before = rows.row("cart_item_copy", "id", 8);
        clock.advance(java.time.Duration.ofMinutes(1));

        assertThat(copyApplier.apply(cartItem(8, 2, false, 1), null).outcome()).isEqualTo(CopyOutcome.OURS_NEWER);
        assertThat(rows.row("cart_item_copy", "id", 8)).isEqualTo(before);
    }
}
