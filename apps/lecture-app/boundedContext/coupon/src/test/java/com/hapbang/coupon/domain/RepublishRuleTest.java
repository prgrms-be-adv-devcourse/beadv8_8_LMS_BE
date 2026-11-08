package com.hapbang.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.hapbang.coupon.domain.RepublishRule.Decision;

// 재발행 대상: 접수 뒤 N초 경과 ∧ DB에 없음 ∧ 보상 완료 아님 ∧ DLT PENDING 아님 ∧ 이전 epoch 접수 아님. PUBLISHED 표식은 보지 않는다
class RepublishRuleTest {

    private static final Duration AFTER = Duration.ofSeconds(30);
    private static final Instant ACCEPTED_AT = Instant.parse("2026-11-08T03:00:00Z");
    private static final Instant DUE = ACCEPTED_AT.plus(AFTER);
    private static final long EPOCH = 3;

    private static IssueRequestRecord record(boolean published, boolean compensated) {
        return new IssueRequestRecord(7, ACCEPTED_AT, ACCEPTED_AT.plus(Duration.ofDays(30)), published, compensated);
    }

    @ParameterizedTest(name = "PUBLISHED={0}")
    @CsvSource({"false", "true"})
    void 대기_시간이_지났고_DB에_없으면_전송_표식과_관계없이_재발행한다(boolean published) {
        assertThat(RepublishRule.decide(record(published, false), DUE, AFTER, EPOCH, false, false))
                .isEqualTo(Decision.REPUBLISH);
        assertThat(RepublishRule.needsCheck(record(published, false), DUE, AFTER, EPOCH)).isTrue();
    }

    @Test
    void 대기_시간_전이면_기다린다() {
        Instant justBefore = DUE.minusMillis(1);

        assertThat(RepublishRule.decide(record(false, false), justBefore, AFTER, EPOCH, false, false))
                .isEqualTo(Decision.WAITING);
        assertThat(RepublishRule.needsCheck(record(false, false), justBefore, AFTER, EPOCH)).isFalse();
    }

    @Test
    void 시계가_접수_시각보다_앞이면_기다린다() {
        assertThat(RepublishRule.decide(record(false, false), ACCEPTED_AT.minusSeconds(60), AFTER, EPOCH, false, false))
                .isEqualTo(Decision.WAITING);
    }

    @Test
    void DB에_결과가_있으면_건너뛴다() {
        assertThat(RepublishRule.decide(record(true, false), DUE, AFTER, EPOCH, true, false))
                .isEqualTo(Decision.RECORDED);
    }

    @Test
    void 보상을_마친_요청은_대기_시간과_DB에_관계없이_보내지_않고_DB도_확인하지_않는다() {
        assertThat(RepublishRule.decide(record(false, true), DUE, AFTER, EPOCH, false, false))
                .isEqualTo(Decision.COMPENSATED);
        assertThat(RepublishRule.decide(record(false, true), ACCEPTED_AT, AFTER, EPOCH, false, false))
                .isEqualTo(Decision.COMPENSATED);
        assertThat(RepublishRule.needsCheck(record(false, true), DUE, AFTER, EPOCH)).isFalse();
    }

    @Test
    void DLT에서_관리자_판단을_기다리는_요청은_보내지_않는다() {
        assertThat(RepublishRule.decide(record(false, false), DUE, AFTER, EPOCH, false, true))
                .isEqualTo(Decision.DLT_PENDING);
    }

    @Test
    void DB_결과가_있으면_DLT_PENDING보다_먼저_건너뛴다() {
        assertThat(RepublishRule.decide(record(false, false), DUE, AFTER, EPOCH, true, true))
                .isEqualTo(Decision.RECORDED);
    }

    @Test
    void 이전_epoch에_접수된_요청은_대기_시간과_DB에_관계없이_보내지_않고_DB도_확인하지_않는다() {
        IssueRequestRecord stale =
                IssueRequestRecord.accepted(7, ACCEPTED_AT, ACCEPTED_AT.plus(Duration.ofDays(30)), EPOCH - 1);

        assertThat(RepublishRule.decide(stale, DUE, AFTER, EPOCH, false, false)).isEqualTo(Decision.STALE_EPOCH);
        assertThat(RepublishRule.decide(stale, ACCEPTED_AT, AFTER, EPOCH, false, false))
                .isEqualTo(Decision.STALE_EPOCH);
        assertThat(RepublishRule.needsCheck(stale, DUE, AFTER, EPOCH)).isFalse();
        assertThat(RepublishRule.staleEpoch(stale, EPOCH)).isTrue();
    }

    @Test
    void 지금_epoch에_접수됐거나_epoch가_없는_옛_형식이면_이전_epoch로_보지_않는다() {
        IssueRequestRecord current =
                IssueRequestRecord.accepted(7, ACCEPTED_AT, ACCEPTED_AT.plus(Duration.ofDays(30)), EPOCH);
        IssueRequestRecord legacy = IssueRequestRecord.accepted(7, ACCEPTED_AT, ACCEPTED_AT.plus(Duration.ofDays(30)));

        assertThat(RepublishRule.decide(current, DUE, AFTER, EPOCH, false, false)).isEqualTo(Decision.REPUBLISH);
        assertThat(RepublishRule.decide(legacy, DUE, AFTER, EPOCH, false, false)).isEqualTo(Decision.REPUBLISH);
        assertThat(RepublishRule.staleEpoch(legacy, EPOCH)).isFalse();
    }
}
