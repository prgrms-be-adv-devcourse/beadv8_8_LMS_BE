package com.hapbang.coupon.global.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.AccessLevel;
import lombok.Getter;

/**
 * 쿠폰의 예상된 업무 실패. 오류 코드의 HTTP 상태와 {@code code}로 응답한다({@link CouponExceptionHandler}).
 * 강의 앱의 업무 예외(BusinessException)와 같은 규칙이다.
 * <p>
 * 상세 메시지(detail)를 주면 응답의 detail로 쓰고, 없으면 오류 코드의 기본 메시지를 쓴다. 응답의 {@code code}는 같다.
 * 응답에 추가 정보가 필요하면 하위 예외에서 {@link #addProperty}로 속성을 더한다. 핸들러가 ProblemDetail 속성으로 그대로 옮긴다.
 */
@Getter
public class CouponException extends RuntimeException {

    /** ProblemDetail 표준 필드와 공통 핸들러가 쓰는 속성. 덮어쓸 수 없다 */
    private static final Set<String> RESERVED_PROPERTIES =
            Set.of("type", "title", "status", "detail", "instance", "code", "errors");

    private final CouponErrorCode errorCode;

    @Getter(AccessLevel.NONE)
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public CouponException(CouponErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public CouponException(CouponErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public CouponException(CouponErrorCode errorCode, Throwable cause) {
        this(errorCode, null, cause);
    }

    public CouponException(CouponErrorCode errorCode, String detail, Throwable cause) {
        super(detail != null ? detail : Objects.requireNonNull(errorCode, "errorCode는 필수입니다.").getMessage(), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode는 필수입니다.");
    }

    /** 응답에 실을 추가 속성 (읽기 전용) */
    public Map<String, Object> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    /** 하위 예외가 응답에 추가 속성을 싣는다. 표준 필드·{@code code}·{@code errors}는 쓸 수 없다 */
    protected void addProperty(String name, Object value) {
        if (RESERVED_PROPERTIES.contains(name)) {
            throw new IllegalArgumentException("예약된 속성은 쓸 수 없습니다: " + name);
        }
        properties.put(name, value);
    }
}
