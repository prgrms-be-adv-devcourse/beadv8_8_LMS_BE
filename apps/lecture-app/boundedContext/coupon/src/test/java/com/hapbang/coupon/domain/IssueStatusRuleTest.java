package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.hapbang.coupon.domain.IssueStatusRule.DbRecord;
import com.hapbang.coupon.domain.IssueStatusRule.RedisLookup;

class IssueStatusRuleTest {

    private static final long COUPON_ID = 5L;

    @ParameterizedTest
    @EnumSource(CouponIssueResult.class)
    void DB_결과가_있으면_Redis와_무관하게_DB_결과다(CouponIssueResult result) {
        IssueStatus expected = result == CouponIssueResult.ISSUED ? IssueStatus.ISSUED : IssueStatus.REJECTED;
        Optional<DbRecord> db = Optional.of(new DbRecord(MEMBER_ID, result));

        assertThat(judge(db, new RedisLookup.Found(MEMBER_ID))).isEqualTo(expected);
        assertThat(judge(db, new RedisLookup.Absent())).isEqualTo(expected);
        assertThat(judge(db, new RedisLookup.Unavailable())).isEqualTo(expected);
    }

    @Test
    void DB_결과가_다른_회원의_것이면_NOT_FOUND다() {
        Optional<DbRecord> db = Optional.of(new DbRecord(OTHER_MEMBER_ID, CouponIssueResult.ISSUED));

        assertThat(judge(db, new RedisLookup.Found(MEMBER_ID))).isEqualTo(IssueStatus.NOT_FOUND);
        assertThat(judge(db, new RedisLookup.Unavailable())).isEqualTo(IssueStatus.NOT_FOUND);
    }

    @Test
    void DB에_없고_Redis에_본인_접수가_있으면_PENDING이다() {
        assertThat(judge(Optional.empty(), new RedisLookup.Found(MEMBER_ID))).isEqualTo(IssueStatus.PENDING);
    }

    @Test
    void DB에_없고_Redis_접수가_다른_회원의_것이면_NOT_FOUND다() {
        assertThat(judge(Optional.empty(), new RedisLookup.Found(OTHER_MEMBER_ID))).isEqualTo(IssueStatus.NOT_FOUND);
    }

    @Test
    void DB에_없고_Redis에도_없으면_NOT_FOUND다() {
        assertThat(judge(Optional.empty(), new RedisLookup.Absent())).isEqualTo(IssueStatus.NOT_FOUND);
    }

    @Test
    void DB에_없고_Redis_장애면_UNKNOWN이다() {
        assertThat(judge(Optional.empty(), new RedisLookup.Unavailable())).isEqualTo(IssueStatus.UNKNOWN);
    }

    @Test
    void 발급_요청_엔티티에서_판정_입력을_만든다() {
        CouponIssueRequest rejected = CouponIssueRequest.rejected("req-1", MEMBER_ID, COUPON_ID, 1L, NOW, "매진", NOW);

        assertThat(rejected.toDbRecord()).isEqualTo(new DbRecord(MEMBER_ID, CouponIssueResult.REJECTED));
    }

    private static IssueStatus judge(Optional<DbRecord> db, RedisLookup redis) {
        return IssueStatusRule.judge(db, redis, MEMBER_ID);
    }
}
