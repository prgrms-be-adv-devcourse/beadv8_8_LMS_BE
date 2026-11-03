package com.hapbang.lecture.app;

import static com.hapbang.lecture.domain.LectureWithdrawalBlockCode.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.lecture.out.WithdrawalBlockTotal;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;

// 집계를 탈퇴 막힌 이유로 바꾸는 규칙 (#625, D75). 집계 쿼리는 LectureWithdrawalBlockRepositoryTest가 맡는다.
class LectureWithdrawalBlocksTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0);

    @Test
    void 이유가_없으면_빈_목록이다() {
        assertThat(LectureWithdrawalBlocks.of(List.of(), 0, NOW).reasons()).isEmpty();
        // 건수가 0인 집계(판매자 집계는 항상 한 행이다)는 이유가 아니다
        assertThat(LectureWithdrawalBlocks.of(List.of(new WithdrawalBlockTotal(LECTURE_SALE_PAYOUT_PENDING, 0, null, 0)),
                0, NOW).reasons()).isEmpty();
    }

    @Test
    void 여러_조건이면_코드_순서대로_이유를_만든다() {
        List<WithdrawalBlockTotal> totals = List.of(
                new WithdrawalBlockTotal(LECTURE_SALE_PAYOUT_PENDING, 3, NOW.plusDays(10), 0),
                new WithdrawalBlockTotal(LECTURE_OBJECTION_FILED, 1, null, 0),
                new WithdrawalBlockTotal(LECTURE_ORDER_HOLDING, 2, NOW.plusDays(5), 0),
                new WithdrawalBlockTotal(LECTURE_ORDER_PROCESSING, 1, null, 0));

        assertThat(LectureWithdrawalBlocks.of(totals, 1, NOW).reasons())
                .extracting(WithdrawalBlockReasonDto::code, WithdrawalBlockReasonDto::message)
                .containsExactly(
                        tuple("LECTURE_ORDER_PROCESSING", "처리 중인 주문 1건이 있어요. 잠시 뒤 다시 시도해 주세요."),
                        tuple("LECTURE_ORDER_HOLDING",
                                "구매한 강의 2건의 이의 제기 기간이 끝나지 않았어요. 기간이 끝난 뒤 탈퇴할 수 있어요."),
                        tuple("LECTURE_OBJECTION_FILED", "처리 중인 이의 제기 1건이 있어요. 결과가 나온 뒤 탈퇴할 수 있어요."),
                        tuple("LECTURE_SALE_PAYOUT_PENDING",
                                "아직 지급되지 않은 판매 수익 3건이 있어요. 지급이 끝난 뒤 탈퇴할 수 있어요."),
                        tuple("LECTURE_ON_SALE",
                                "판매 중이거나 검수 대기 중인 강의 1개가 있어요. 강의를 삭제한 뒤 탈퇴할 수 있어요."));
    }

    @Test
    void 건수는_천_단위_쉼표로_쓴다() {
        assertThat(LectureWithdrawalBlocks.of(List.of(), 1_234, NOW).reasons())
                .singleElement()
                .extracting(WithdrawalBlockReasonDto::message)
                .isEqualTo("판매 중이거나 검수 대기 중인 강의 1,234개가 있어요. 강의를 삭제한 뒤 탈퇴할 수 있어요.");
    }

    @Test
    void availableAt은_가장_늦은_지급_예정_시각의_날짜다() {
        // 강의 시각은 KST 날짜·시각으로 저장한다 (D37)
        List<WithdrawalBlockTotal> totals = List.of(
                new WithdrawalBlockTotal(LECTURE_ORDER_HOLDING, 2, LocalDateTime.of(2026, 11, 21, 0, 0), 0),
                new WithdrawalBlockTotal(LECTURE_SALE_PAYOUT_PENDING, 1, LocalDateTime.of(2026, 12, 1, 0, 0), 0));

        assertThat(LectureWithdrawalBlocks.of(totals, 0, NOW).reasons())
                .extracting(WithdrawalBlockReasonDto::availableAt)
                .containsExactly(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 12, 1));
    }

    @Test
    void 날짜를_정할_수_없는_항목이_있으면_availableAt은_null이다() {
        // 지급 중단(이의 제기)·지급 실패 항목은 기다려도 풀린다고 말할 수 없다
        List<WithdrawalBlockTotal> totals = List.of(
                new WithdrawalBlockTotal(LECTURE_ORDER_HOLDING, 2, NOW.plusDays(5), 1),
                new WithdrawalBlockTotal(LECTURE_SALE_PAYOUT_PENDING, 3, NOW.plusDays(10), 1));

        assertThat(LectureWithdrawalBlocks.of(totals, 0, NOW).reasons())
                .extracting(WithdrawalBlockReasonDto::availableAt)
                .containsOnlyNulls();
    }

    @Test
    void 가장_늦은_지급_예정_시각이_지났으면_availableAt은_null이다() {
        // 지급 처리 중이라 곧 풀린다. 지난 날짜를 보여 주지 않는다
        List<WithdrawalBlockTotal> totals = List.of(
                new WithdrawalBlockTotal(LECTURE_ORDER_HOLDING, 1, NOW, 0),
                new WithdrawalBlockTotal(LECTURE_SALE_PAYOUT_PENDING, 1, NOW.minusDays(1), 0));

        assertThat(LectureWithdrawalBlocks.of(totals, 0, NOW).reasons())
                .extracting(WithdrawalBlockReasonDto::availableAt)
                .containsOnlyNulls();
    }

    @Test
    void 기다려서_풀리지_않는_이유는_availableAt이_없다() {
        List<WithdrawalBlockTotal> totals = List.of(
                new WithdrawalBlockTotal(LECTURE_ORDER_PROCESSING, 1, NOW.plusDays(30), 0),
                new WithdrawalBlockTotal(LECTURE_OBJECTION_FILED, 1, NOW.plusDays(30), 0));

        assertThat(LectureWithdrawalBlocks.of(totals, 1, NOW).reasons())
                .extracting(WithdrawalBlockReasonDto::availableAt)
                .containsOnlyNulls();
    }
}
