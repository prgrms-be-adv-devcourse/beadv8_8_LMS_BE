package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CouponDltLogTest {

    private static final CouponDltLog.Source SOURCE = new CouponDltLog.Source("coupon.events.v1", 1, 7L, "42");

    private static final Duration LEASE = Duration.ofMinutes(1);

    private static CouponDltLog pending() {
        return CouponDltLog.isolate(SOURCE, 42L, "req-1", "{}", "원인", NOW);
    }

    private static CouponDltLog replaying() {
        CouponDltLog log = pending();
        log.claimReplay(ADMIN_ID, NOW, LEASE);
        return log;
    }

    @ParameterizedTest(name = "{0} → {1}: {2}")
    @CsvSource({
            "PENDING, REPLAYING, true", "PENDING, REJECTED, true", "PENDING, REPLAYED, false", "PENDING, PENDING, false",
            "REPLAYING, REPLAYED, true", "REPLAYING, PENDING, true", "REPLAYING, REJECTED, false",
            "REPLAYING, REPLAYING, false",
            "REPLAYED, REJECTED, false", "REPLAYED, PENDING, false", "REPLAYED, REPLAYING, false",
            "REJECTED, REPLAYING, false", "REJECTED, PENDING, false", "REJECTED, REJECTED, false"
    })
    void 재발행은_REPLAYING을_거치고_끝난_기록은_다시_바뀌지_않는다(CouponDltStatus from, CouponDltStatus to,
                                                  boolean expected) {
        assertThat(from.canTransitTo(to)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({"PENDING, true", "REPLAYING, true", "REPLAYED, false", "REJECTED, false"})
    void PENDING과_REPLAYING을_미처리로_본다(CouponDltStatus status, boolean expected) {
        assertThat(status.isUnresolved()).isEqualTo(expected);
    }

    @Test
    void 격리_기록은_PENDING으로_시작하고_긴_실패_원인은_칸_길이로_자른다() {
        CouponDltLog log = CouponDltLog.isolate(SOURCE, 42L, "req-1", "{}", "x".repeat(2_000), NOW);

        assertThat(log.getStatus()).isEqualTo(CouponDltStatus.PENDING);
        assertThat(log.isPending()).isTrue();
        assertThat(log.getFailureCause()).hasSize(CouponDltLog.MAX_FAILURE_CAUSE);
        assertThat(log.getSourceOffset()).isEqualTo(7L);
        assertThat(log.getMessageKey()).isEqualTo("42");
    }

    @Test
    void 재발행_선점은_REPLAYING이고_선점_시각과_관리자를_남기며_ACK를_받으면_REPLAYED다() {
        CouponDltLog log = pending();

        log.claimReplay(ADMIN_ID, NOW.plusSeconds(1), LEASE);

        assertThat(log.getStatus()).isEqualTo(CouponDltStatus.REPLAYING);
        assertThat(log.getReplayClaimedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(log.getHandledBy()).isEqualTo(ADMIN_ID);
        assertThat(log.getHandledAt()).isEqualTo(NOW.plusSeconds(1));

        log.completeReplay(NOW.plusSeconds(2));

        assertThat(log.getStatus()).isEqualTo(CouponDltStatus.REPLAYED);
        assertThat(log.getReplayClaimedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(log.getUpdatedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void REPLAYING은_선점_lease가_지나야_다시_선점하거나_거절할_수_있다() {
        CouponDltLog log = replaying();
        Instant beforeLease = NOW.plus(LEASE).minusMillis(1);

        assertThat(log.canClaim(beforeLease, LEASE)).isFalse();
        assertThatThrownBy(() -> log.claimReplay(OTHER_MEMBER_ID, beforeLease, LEASE))
                .isInstanceOf(CouponDomainException.class)
                .extracting("error").isEqualTo(CouponDomainError.INVALID_STATUS_TRANSITION);
        assertThatThrownBy(() -> log.reject(OTHER_MEMBER_ID, "사유", beforeLease, LEASE))
                .isInstanceOf(CouponDomainException.class);

        assertThat(log.canClaim(NOW.plus(LEASE), LEASE)).isTrue();
        log.claimReplay(OTHER_MEMBER_ID, NOW.plus(LEASE), LEASE);

        assertThat(log.getStatus()).isEqualTo(CouponDltStatus.REPLAYING);
        assertThat(log.getReplayClaimedAt()).isEqualTo(NOW.plus(LEASE));
        assertThat(log.getHandledBy()).isEqualTo(OTHER_MEMBER_ID);
    }

    @Test
    void 선점_lease가_지난_REPLAYING은_영구_거절할_수_있다() {
        CouponDltLog log = replaying();

        assertThat(log.reject(OTHER_MEMBER_ID, "멈춘 재발행 거절", NOW.plus(LEASE), LEASE)).isTrue();

        assertThat(log.getStatus()).isEqualTo(CouponDltStatus.REJECTED);
        assertThat(log.getHandledBy()).isEqualTo(OTHER_MEMBER_ID);
    }

    @Test
    void 영구_거절하면_REJECTED와_사유이고_다시_거절하면_바꾸지_않고_false다() {
        CouponDltLog log = pending();

        assertThat(log.reject(ADMIN_ID, "원인 확인 후 거절", NOW.plusSeconds(1), LEASE)).isTrue();
        assertThat(log.reject(OTHER_MEMBER_ID, "다른 사유", NOW.plusSeconds(2), LEASE)).isFalse();

        assertThat(log.getStatus()).isEqualTo(CouponDltStatus.REJECTED);
        assertThat(log.getRejectReason()).isEqualTo("원인 확인 후 거절");
        assertThat(log.getHandledBy()).isEqualTo(ADMIN_ID);
        assertThat(log.getHandledAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void 처리한_기록은_다른_상태로_다시_바뀌지_않는다() {
        CouponDltLog replayed = replaying();
        replayed.completeReplay(NOW);
        CouponDltLog rejected = pending();
        rejected.reject(ADMIN_ID, "사유", NOW, LEASE);
        Instant later = NOW.plus(LEASE).plusSeconds(1);

        assertThatThrownBy(() -> replayed.claimReplay(ADMIN_ID, later, LEASE)).isInstanceOf(CouponDomainException.class)
                .extracting("error").isEqualTo(CouponDomainError.INVALID_STATUS_TRANSITION);
        assertThatThrownBy(() -> replayed.reject(ADMIN_ID, "사유", later, LEASE)).isInstanceOf(CouponDomainException.class);
        assertThatThrownBy(() -> rejected.claimReplay(ADMIN_ID, later, LEASE)).isInstanceOf(CouponDomainException.class);
        assertThatThrownBy(() -> pending().completeReplay(NOW)).isInstanceOf(CouponDomainException.class);
    }

    @Test
    void 선점한_재발행의_전송이_실패하면_PENDING으로_되돌리고_처리_기록과_선점_시각을_지운다() {
        CouponDltLog replaying = replaying();

        replaying.revertReplay(NOW.plusSeconds(1));

        assertThat(replaying.getStatus()).isEqualTo(CouponDltStatus.PENDING);
        assertThat(replaying.getReplayClaimedAt()).isNull();
        assertThat(replaying.getHandledBy()).isNull();
        assertThat(replaying.getHandledAt()).isNull();
        assertThat(replaying.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThatThrownBy(() -> replaying.revertReplay(NOW)).isInstanceOf(CouponDomainException.class);
        CouponDltLog rejected = pending();
        rejected.reject(ADMIN_ID, "사유", NOW, LEASE);
        assertThatThrownBy(() -> rejected.revertReplay(NOW)).isInstanceOf(CouponDomainException.class);
    }

    @Test
    void 거절_사유가_없으면_거절하지_않는다() {
        CouponDltLog log = pending();

        assertThatThrownBy(() -> log.reject(ADMIN_ID, " ", NOW, LEASE)).isInstanceOf(CouponDomainException.class)
                .extracting("error").isEqualTo(CouponDomainError.REJECT_REASON_REQUIRED);
        assertThat(log.isPending()).isTrue();
    }

    @Test
    void 보상_결과는_영구_거절한_기록에만_남긴다() {
        CouponDltLog log = pending();
        assertThatThrownBy(() -> log.recordCompensation(IssueCompensation.RESTORED, NOW))
                .isInstanceOf(CouponDomainException.class);

        log.reject(ADMIN_ID, "사유", NOW, LEASE);
        log.recordCompensation(IssueCompensation.RESTORED, NOW);

        assertThat(log.getCompensationResult()).isEqualTo(IssueCompensation.RESTORED);
    }
}
