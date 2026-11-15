package com.hapbang.lecture.out.coupon;

import java.util.Objects;

/**
 * 쿠폰 내부 API 호출 한 번의 결과. 네 가지 중 하나다.
 * <ul>
 *     <li>{@link Succeeded}: 쿠폰이 처리했고 응답을 읽었다.</li>
 *     <li>{@link Rejected}: 쿠폰이 4xx로 거절했다. 쿠폰 오류 코드와 실패한 발급 쿠폰 ID를 그대로 싣는다.</li>
 *     <li>{@link NotSent}: 요청이 쿠폰에 닿지 않은 것이 확실하다(회로 열림, 연결 실패, 처리하지 않았다는 응답).
 *     같은 내용으로 다시 보내도 된다.</li>
 *     <li>{@link Unknown}: 쿠폰이 처리했는지 모른다(응답 시간 초과, 연결 시간 초과, 5xx, 읽을 수 없는 성공 응답).
 *     성공으로도 실패로도 치지 않는다. 예약·해제는 같은 workId·같은 내용으로 다시 보내거나 예약 상태로 확인한다.</li>
 * </ul>
 *
 * @param <T> 성공 응답의 {@code data} 타입
 */
public sealed interface CouponCallResult<T> {

    /** @param data 쿠폰 성공 응답의 {@code data} */
    record Succeeded<T>(T data) implements CouponCallResult<T> {

        public Succeeded {
            Objects.requireNonNull(data, "data는 필수입니다.");
        }
    }

    /**
     * @param status         응답 상태
     * @param code           쿠폰 오류 코드(ProblemDetail {@code code}). 본문이 없으면(예: 401) null
     * @param memberCouponId 특정 발급 쿠폰 때문에 실패했으면 그 ID, 아니면 null
     * @param detail         쿠폰이 준 설명. 없으면 null
     */
    record Rejected<T>(int status, String code, Long memberCouponId, String detail) implements CouponCallResult<T> {
    }

    /** @param reason 보내지 않은 이유(로그용) */
    record NotSent<T>(String reason) implements CouponCallResult<T> {
    }

    /**
     * @param status 응답 상태. 응답을 받지 못했으면 0
     * @param reason 결과를 모르는 이유(로그용)
     */
    record Unknown<T>(int status, String reason) implements CouponCallResult<T> {
    }
}
