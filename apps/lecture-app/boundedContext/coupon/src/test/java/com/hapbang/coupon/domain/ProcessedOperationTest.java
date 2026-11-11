package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ProcessedOperationTest {

    @Test
    void 같은_작업_ID의_기록과_내용이_같은지로_재전달과_충돌을_가른다() {
        IssueDelivery delivery = new IssueDelivery("req-1", 42L, MEMBER_ID, 1, NOW, NOW.plus(Duration.ofDays(30)));
        ProcessedOperation operation = ProcessedOperation.of(delivery, CouponIssueResult.ISSUED, NOW);

        assertThat(operation.getOperationId()).isEqualTo("coupon-issue:42:req-1");
        assertThat(operation.getResult()).isEqualTo("ISSUED");
        assertThat(operation.sameContent(delivery)).isTrue();
        assertThat(operation.sameContent(new IssueDelivery("req-1", 42L, OTHER_MEMBER_ID, 1, NOW,
                NOW.plus(Duration.ofDays(30))))).isFalse();
    }
}
