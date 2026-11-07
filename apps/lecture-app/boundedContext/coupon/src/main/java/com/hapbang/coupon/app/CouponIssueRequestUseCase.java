package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.IssueRequestResult;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueAttempt;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueOutcome;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.IssueResponseCode;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;

import lombok.extern.slf4j.Slf4j;

/**
 * 선착순 발급 접수. 접수 판정(운영 상태·준비·기간·회원 중복·재고)과 epoch·만료 시각은 Redis Lua가 정하고, DB를 읽지 않는다
 * (발급 경로는 Redis만 쓴다. DB 커넥션 풀을 다른 기능과 함께 쓰므로 접수 폭주가 DB로 번지지 않게 한다).
 * 쿠폰이 없거나 준비 전이면 Lua가 준비 안 됨(-2)이라 409 {@code COUPON_ISSUE_NOT_OPEN}이다.
 * 접수(ACCEPTED)는 완료가 아니다. 결과는 발급 요청 상태 조회로 확인한다.
 * <p>
 * 같은 요청 ID를 다시 보내면 Lua가 기존 접수 기록을 바꾸지 않고 2(ALREADY_ACCEPTED)와 최초 접수의 만료 시각을 돌려준다.
 * 발급이 중단된 뒤의 재전송은 Lua 검사 순서상 NOT_OPEN이다.
 */
@Slf4j
@Service
public class CouponIssueRequestUseCase {

    private final CouponIssueStore couponIssueStore;
    private final Clock clock;

    public CouponIssueRequestUseCase(CouponIssueStore couponIssueStore,
                                     @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponIssueStore = couponIssueStore;
        this.clock = clock;
    }

    public IssueRequestResult requestIssue(long couponId, long memberId, String issueRequestId) {
        IssueRequestIds.requireValid(issueRequestId);
        // Redis 접수 기록은 밀리초까지만 남으므로 접수 시각도 밀리초로 자른다
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        IssueOutcome outcome = couponIssueStore.tryIssue(new IssueAttempt(couponId, memberId, issueRequestId, issuedAt));
        IssueResponseCode code = IssueResponseCode.fromLua(outcome.code());
        if (!code.isAccepted()) {
            throw new CouponException(errorCodeOf(code));
        }
        if (code == IssueResponseCode.ACCEPTED) {
            log.info("쿠폰 발급을 접수했다 couponId={}, memberId={}, issueRequestId={}", couponId, memberId, issueRequestId);
        }
        return new IssueRequestResult(issueRequestId, couponId, code, outcome.expiresAt());
    }

    private static CouponErrorCode errorCodeOf(IssueResponseCode code) {
        return switch (code) {
            case SOLD_OUT -> CouponErrorCode.COUPON_SOLD_OUT;
            case ALREADY_ISSUED -> CouponErrorCode.COUPON_ALREADY_ISSUED;
            case NOT_OPEN -> CouponErrorCode.COUPON_ISSUE_NOT_OPEN;
            case NOT_IN_PERIOD -> CouponErrorCode.COUPON_ISSUE_NOT_IN_PERIOD;
            case REQUEST_ID_CONFLICT -> CouponErrorCode.ISSUE_REQUEST_ID_CONFLICT;
            case ERROR -> CouponErrorCode.COUPON_ISSUE_FAILED;
            case ACCEPTED, ALREADY_ACCEPTED -> throw new IllegalArgumentException("접수된 결과는 오류가 아니다: " + code);
        };
    }

    /**
     * 발급 요청 ID 형식: 1~64자 영문·숫자·{@code _}·{@code -}. 클라이언트가 만든 멱등 키라 Redis Hash field와 DB PK(100자)에 그대로 쓴다.
     */
    static final class IssueRequestIds {

        private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

        private IssueRequestIds() {
        }

        static void requireValid(String issueRequestId) {
            if (issueRequestId == null || !FORMAT.matcher(issueRequestId).matches()) {
                throw new CouponException(CouponErrorCode.INVALID_ISSUE_REQUEST_ID);
            }
        }
    }
}
