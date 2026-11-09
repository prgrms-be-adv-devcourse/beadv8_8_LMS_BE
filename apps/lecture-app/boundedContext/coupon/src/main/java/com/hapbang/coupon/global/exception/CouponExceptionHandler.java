package com.hapbang.coupon.global.exception;

import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.LectureCatalogUnavailableException;
import com.hapbang.coupon.domain.CouponDomainError;
import com.hapbang.coupon.domain.CouponDomainException;
import com.hapbang.coupon.domain.usage.CouponUsageViolationException;

import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 컨트롤러의 업무 예외({@link CouponException})를 ProblemDetail(RFC 9457) + {@code code}로 응답한다.
 * 응답 모양은 강의 앱 공통 핸들러의 업무 예외 응답과 같다(status, detail, code, 추가 속성).
 * <ul>
 *     <li>쿠폰 패키지의 컨트롤러에만 걸리고, 강의 공통 핸들러보다 먼저 고르도록 우선순위를 가장 높게 둔다. 그러지 않으면 강의 공통
 *     핸들러의 {@code Exception} 처리가 쿠폰 예외를 500으로 응답한다.</li>
 *     <li>쿠폰 예외({@link CouponException}, 도메인 규칙 위반 {@link CouponDomainException}, 발급 저장소·강의 조회 연결 실패)와
 *     쿠폰 저장 중 낙관적 락 충돌만 처리한다.
 *     요청 값 검증·권한 거절·없는 경로·처리하지 못한 예외는 여기서 고르지 않으므로 같은 JVM의
 *     강의 공통 핸들러(패키지 제한 없음)가 공통 코드로 응답한다. 쿠폰은 강의의 global을 참조할 수 없어 자기 예외만 여기서 바꾼다.</li>
 *     <li>다른 예외를 오류 코드로 바꿀 때도 {@link #respond}로 응답한다(같은 형식·같은 로그).</li>
 * </ul>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.hapbang.coupon")
public class CouponExceptionHandler {

    @ExceptionHandler(CouponException.class)
    public ResponseEntity<ProblemDetail> handleCouponException(CouponException exception) {
        return respond(exception.getErrorCode(), exception.getMessage(), exception.getProperties(), exception);
    }

    /**
     * 도메인 규칙 위반. detail은 어느 규칙인지 알리는 도메인 메시지다.
     * 사용 규칙 위반({@link CouponUsageViolationException})은 실패한 발급 쿠폰 ID를 {@code memberCouponId} 속성으로 싣는다
     */
    @ExceptionHandler(CouponDomainException.class)
    public ResponseEntity<ProblemDetail> handleCouponDomainException(CouponDomainException exception) {
        Map<String, Object> properties = exception instanceof CouponUsageViolationException violation
                ? Map.of(CouponUsageException.MEMBER_COUPON_ID, violation.getMemberCouponId())
                : Map.of();
        return respond(errorCodeOf(exception.getError()), exception.getMessage(), properties, exception);
    }

    /** 발급 저장소(Redis)에 닿지 못했다. 내부 메시지는 응답에 싣지 않는다 */
    @ExceptionHandler(CouponIssueStoreUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleIssueStoreUnavailable(CouponIssueStoreUnavailableException exception) {
        return respond(CouponErrorCode.COUPON_ISSUE_STORE_UNAVAILABLE, null, Map.of(), exception);
    }

    /** 강의 앱 내부 API에 닿지 못했다. 내부 메시지는 응답에 싣지 않는다 */
    @ExceptionHandler(LectureCatalogUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleLectureCatalogUnavailable(LectureCatalogUnavailableException exception) {
        return respond(CouponErrorCode.LECTURE_CATALOG_UNAVAILABLE, null, Map.of(), exception);
    }

    /**
     * 쿠폰 저장 중 낙관적 락(@Version)이 충돌했다. 다른 요청이 먼저 바꿨을 뿐이라 409로 다시 시도하게 한다.
     * JPA가 바꾼 {@code ObjectOptimisticLockingFailureException}도 이 타입의 하위라 함께 받는다.
     * 쿠폰 패키지의 컨트롤러에서 난 것만 여기 온다(다른 컨트롤러는 강의 공통 핸들러가 받는다)
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(OptimisticLockingFailureException exception) {
        return respond(CouponErrorCode.COUPON_CONCURRENT_UPDATE, null, Map.of(), exception);
    }

    /** 도메인 오류의 응답 코드. 도메인 오류가 늘면 컴파일 오류로 여기를 고치게 한다(default를 두지 않는다) */
    static CouponErrorCode errorCodeOf(CouponDomainError error) {
        return switch (error) {
            case MISSING_TERMS, INVALID_NAME, INVALID_QUANTITY, INVALID_ISSUE_PERIOD, INVALID_RATE, INVALID_AMOUNT,
                 MAX_DISCOUNT_REQUIRED, MAX_DISCOUNT_NOT_ALLOWED, SELLER_TARGET_LECTURE_ONLY, LECTURE_TARGET_REQUIRED,
                 LECTURE_TARGET_NOT_ALLOWED, INVALID_BASE_PRICE, INVALID_RELATIVE_EXPIRY, INVALID_FIXED_EXPIRY,
                 EXPIRY_TOO_LONG, REJECT_REASON_REQUIRED, BUDGET_OVERFLOW -> CouponErrorCode.INVALID_COUPON_POLICY;
            case ISSUE_PERIOD_ENDED -> CouponErrorCode.COUPON_ISSUE_PERIOD_ENDED;
            case INVALID_STATUS_TRANSITION, BUDGET_NOT_RESERVED, REDIS_NOT_READY, BUDGET_RESERVATION_MISMATCH,
                 INVALID_BUDGET_WORK_TRANSITION, BUDGET_NOT_APPLICABLE -> CouponErrorCode.INVALID_COUPON_STATUS;
            case LECTURE_NOT_ON_SALE -> CouponErrorCode.COUPON_LECTURE_NOT_ON_SALE;
            case UNKNOWN_LUA_RESULT -> CouponErrorCode.COUPON_ISSUE_FAILED;
            case COUPON_NOT_FOUND -> CouponErrorCode.COUPON_NOT_FOUND;
            case NOT_COUPON_OWNER -> CouponErrorCode.NOT_COUPON_OWNER;
            case COUPON_NOT_APPLICABLE -> CouponErrorCode.COUPON_NOT_APPLICABLE;
            case COUPON_NOT_USABLE -> CouponErrorCode.COUPON_NOT_USABLE;
            case COUPON_STACKING_VIOLATION -> CouponErrorCode.COUPON_STACKING_VIOLATION;
        };
    }

    /**
     * 오류 코드의 상태로 응답한다. 5xx는 원인과 함께 ERROR, 4xx는 사용자 입력·정책상 거절한 정상 응답이라 INFO로 남긴다
     * (docs/dev/logging.md "레벨", 강의 공통 핸들러와 같다).
     *
     * @param detail     응답의 detail. {@code null}이면 오류 코드의 기본 메시지
     * @param properties 응답에 더할 속성
     */
    ResponseEntity<ProblemDetail> respond(CouponErrorCode errorCode, String detail, Map<String, Object> properties,
                                          Throwable exception) {
        String message = detail != null ? detail : errorCode.getMessage();
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(errorCode.getStatus(), message);
        problemDetail.setProperty("code", errorCode.getCode());
        properties.forEach(problemDetail::setProperty);
        if (errorCode.getStatus().is5xxServerError()) {
            log.error("쿠폰 업무 예외로 응답했다 code={}, message={}", errorCode.getCode(), message, exception);
        } else {
            log.info("쿠폰 업무 예외로 응답했다 code={}, message={}", errorCode.getCode(), message);
        }
        return ResponseEntity.status(errorCode.getStatus()).body(problemDetail);
    }
}
