package com.hapbang.lecture.app;

import com.hapbang.global.exception.BusinessException;
import com.hapbang.lecture.out.billing.BillingCallException;

/**
 * 지급 실패 분류 (D54). 다음 실행에서 다시 시도할 일시 실패와, 다시 시도해도 풀리지 않아 격리할 영구 실패로 나눈다.
 * billing·shared 계약을 바꾸지 않도록 billing 오류는 코드 문자열로 구분한다.
 * <ul>
 *     <li>영구: 지갑 없음({@code WALLET_NOT_FOUND})이 아닌 업무 예외({@code HOLD_ALREADY_CLOSED},
 *     {@code PAYOUT_ALREADY_SETTLED} 등), 지급액 불일치</li>
 *     <li>일시: 지갑 없음, 그 밖의 예외(낙관적 락 충돌, 교착 등 DB 오류, 모르는 예외)</li>
 * </ul>
 *
 * @param code      실패 기록에 남길 코드. 업무 예외는 오류 코드, 그 밖에는 예외 클래스 이름이다 (최대 100자)
 * @param permanent 영구 실패인지
 */
record PayoutFailure(String code, boolean permanent) {

    /** {@code order_item.last_payout_failure_code} 길이 */
    private static final int MAX_CODE_LENGTH = 100;
    /** 판매자 지갑이 생기면 같은 지급 요청으로 지급된다 */
    private static final String WALLET_NOT_FOUND = "WALLET_NOT_FOUND";

    static PayoutFailure of(RuntimeException exception) {
        RuntimeException diagnostic = diagnosticException(exception);
        if (diagnostic instanceof BusinessException businessException) {
            String code = businessException.getErrorCode().getCode();
            return new PayoutFailure(truncate(code), !WALLET_NOT_FOUND.equals(code));
        }
        if (diagnostic instanceof PayoutAmountMismatchException) {
            return new PayoutFailure(PayoutAmountMismatchException.CODE, true);
        }
        String name = diagnostic.getClass().getSimpleName();
        return new PayoutFailure(truncate(name.isEmpty() ? diagnostic.getClass().getName() : name), false);
    }

    /**
     * 분류·로그에 쓸 예외. billing 결과를 받지 못한 실패({@link BillingCallException})는 감싼 원인 예외다.
     * billing 예외를 그대로 받던 때와 같은 실패 코드·로그를 남기기 위한 임시 호환이며, #250에서 HP 작업으로 옮길 때 없앤다 (D62).
     */
    static RuntimeException diagnosticException(RuntimeException exception) {
        if (exception instanceof BillingCallException && exception.getCause() instanceof RuntimeException cause) {
            return cause;
        }
        return exception;
    }

    private static String truncate(String code) {
        return code.length() <= MAX_CODE_LENGTH ? code : code.substring(0, MAX_CODE_LENGTH);
    }
}
