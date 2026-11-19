package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponCreateTest.assertError;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LectureSaleRuleTest {

    @Test
    void 판매_중인_강의는_통과한다() {
        assertThat(LectureSaleRule.isOnSale("ON_SALE")).isTrue();
        assertThatCode(() -> LectureSaleRule.requireOnSale("ON_SALE")).doesNotThrowAnyException();
    }

    // 강의 앱이 새 상태를 더해도(모르는 값) 판매 중으로 보지 않는다
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PENDING_INSPECTION", "REJECTED", "on_sale", "SUSPENDED"})
    void 판매_중이_아닌_강의는_거절한다(String saleStatus) {
        assertThat(LectureSaleRule.isOnSale(saleStatus)).isFalse();
        assertError(() -> LectureSaleRule.requireOnSale(saleStatus), CouponDomainError.LECTURE_NOT_ON_SALE);
    }
}
