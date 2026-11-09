package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IssueCompensationTest {

    @ParameterizedTest(name = "Lua {0} → {1}, 재고 복구 {2}, 끝남 {3}")
    @CsvSource({
            "1, RESTORED, true, true",
            "2, ALREADY_COMPENSATED, false, true",
            "3, MARKED_ONLY, false, true",
            "0, NO_RECORD, false, false",
            "-4, MEMBER_MISMATCH, false, false"
    })
    void 재고는_첫_보상에서만_되돌리고_이미_보상했거나_이전_epoch면_바꾸지_않는다(long code, IssueCompensation expected,
                                                                 boolean stockRestored, boolean settled) {
        IssueCompensation result = IssueCompensation.fromLua(code);

        assertThat(result).isEqualTo(expected);
        assertThat(result.isStockRestored()).isEqualTo(stockRestored);
        assertThat(result.isSettled()).isEqualTo(settled);
    }

    @Test
    void 오류나_모르는_반환값은_성공으로_보지_않는다() {
        assertThatThrownBy(() -> IssueCompensation.fromLua(-9)).isInstanceOf(CouponDomainException.class)
                .extracting("error").isEqualTo(CouponDomainError.UNKNOWN_LUA_RESULT);
        assertThatThrownBy(() -> IssueCompensation.fromLua(4)).isInstanceOf(CouponDomainException.class);
    }

    @Test
    void 보상_결과는_거절한_발급_요청에만_남긴다() {
        Instant acceptedAt = NOW;
        CouponIssueRequest rejected = CouponIssueRequest.rejected("req-1", MEMBER_ID, 1L, 1, acceptedAt, "사유", NOW);
        CouponIssueRequest issued = CouponIssueRequest.issued("req-2", MEMBER_ID, 1L, 1, acceptedAt, 9L, NOW);

        rejected.recordCompensation(IssueCompensation.RESTORED);

        assertThat(rejected.getCompensationStatus()).isEqualTo("RESTORED");
        assertThatThrownBy(() -> issued.recordCompensation(IssueCompensation.RESTORED))
                .isInstanceOf(CouponDomainException.class);
    }
}
