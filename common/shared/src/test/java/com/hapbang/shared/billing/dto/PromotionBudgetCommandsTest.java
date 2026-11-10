package com.hapbang.shared.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// 쿠폰 예산 요청 DTO가 요청 구조(작업 ID·예약 ID 형식, 쿠폰 ID, 정책 버전, 해제 대상·사유 길이)만 검사하고 금액은 검사하지 않는지 검증한다.
class PromotionBudgetCommandsTest {

    @Test
    @DisplayName("예약 요청은 금액을 검사하지 않는다(0 이하는 지갑이 INVALID_POINT_AMOUNT로 거절한다)")
    void reserveDoesNotCheckAmount() {
        assertThatCode(() -> new PromotionBudgetReserveCommand("coupon-42-reserve-v1", 42L, 1, 0L))
                .doesNotThrowAnyException();
        assertThatCode(() -> new PromotionBudgetReleaseCommand("coupon-42-release-v1", "PB-7", -1L, "ADMIN", null))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "coupon:42", "쿠폰-42", "a/b"})
    @DisplayName("작업 ID는 영문·숫자·-·_ 1~200자이고 ':'를 쓸 수 없다")
    void workIdFormat(String workId) {
        assertThatThrownBy(() -> new PromotionBudgetReserveCommand(workId, 42L, 1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromotionBudgetReleaseCommand(workId, "PB-7", 1L, "ADMIN", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromotionBudgetRequests.requireWorkId(workId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("작업 ID는 200자까지 받고 201자는 거절한다. null도 거절한다")
    void workIdLength() {
        assertThatCode(() -> new PromotionBudgetReserveCommand("x".repeat(200), 42L, 1, 1L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new PromotionBudgetReserveCommand("x".repeat(201), 42L, 1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromotionBudgetReserveCommand(null, 42L, 1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("예약 요청의 쿠폰 ID는 양수, 정책 버전은 1 이상이다")
    void reserveStructure() {
        assertThatThrownBy(() -> new PromotionBudgetReserveCommand("w", 0L, 1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromotionBudgetReserveCommand("w", 42L, 0, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "PB-", "PB-0", "PB-07", "pb-7", "PB-7a", "PB-1234567890123456789"})
    @DisplayName("예약 ID는 PB-{양수}이고 형식이 틀리면 거절한다")
    void reservationIdFormat(String reservationId) {
        assertThatThrownBy(() -> new PromotionBudgetReleaseCommand("w", reservationId, 1L, "ADMIN", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromotionBudgetRequests.reservationRowId(reservationId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("예약 행 ID와 예약 ID는 PB-{ID}로 서로 바뀐다")
    void reservationIdConversion() {
        assertThat(PromotionBudgetRequests.reservationId(7L)).isEqualTo("PB-7");
        assertThat(PromotionBudgetRequests.reservationRowId("PB-7")).isEqualTo(7L);
        assertThat(PromotionBudgetRequests.reservationRowId("PB-123456789012345678")).isEqualTo(123_456_789_012_345_678L);
        assertThatThrownBy(() -> PromotionBudgetRequests.reservationId(0L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("해제 대상은 필수이고 1~200자, 사유는 선택이고 500자 이하다")
    void releaseTargetAndReason() {
        assertThatThrownBy(() -> new PromotionBudgetReleaseCommand("w", "PB-7", 1L, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PromotionBudgetReleaseCommand("w", "PB-7", 1L, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromotionBudgetReleaseCommand("w", "PB-7", 1L, "x".repeat(201), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromotionBudgetReleaseCommand("w", "PB-7", 1L, "ADMIN", "x".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new PromotionBudgetReleaseCommand("w", "PB-7", 1L, "x".repeat(200), "x".repeat(500)))
                .doesNotThrowAnyException();
    }
}
