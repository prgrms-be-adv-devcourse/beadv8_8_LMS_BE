package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponCreateTest.assertError;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IssueResponseCodeTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "1, ACCEPTED, true",
            "2, ALREADY_ACCEPTED, true",
            "0, SOLD_OUT, false",
            "-1, ALREADY_ISSUED, false",
            "-2, NOT_OPEN, false",
            "-3, NOT_IN_PERIOD, false",
            "-4, REQUEST_ID_CONFLICT, false",
            "-9, ERROR, false"
    })
    void Lua_반환값을_응답_코드로_바꾼다(long luaValue, IssueResponseCode expected, boolean accepted) {
        IssueResponseCode code = IssueResponseCode.fromLua(luaValue);

        assertThat(code).isEqualTo(expected);
        assertThat(code.isAccepted()).isEqualTo(accepted);
    }

    @ParameterizedTest
    @ValueSource(longs = {3, -5, -8, -10, Long.MAX_VALUE})
    void 알_수_없는_반환값은_오류다(long luaValue) {
        assertError(() -> IssueResponseCode.fromLua(luaValue), CouponDomainError.UNKNOWN_LUA_RESULT);
    }
}
