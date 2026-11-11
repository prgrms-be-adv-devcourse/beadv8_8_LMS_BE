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
        assertThatCode(() -> LectureSaleRule.requireOnSale("ON_SALE")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PENDING_INSPECTION", "REJECTED"})
    void 판매_중이_아닌_강의는_거절한다(String saleStatus) {
        assertError(() -> LectureSaleRule.requireOnSale(saleStatus), CouponDomainError.LECTURE_NOT_ON_SALE);
    }
}
