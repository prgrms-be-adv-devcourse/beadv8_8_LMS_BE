package com.hapbang.appearance.domain;

/**
 * 지갑이 HP 보류를 거절했다 (#274). 요청서·모집글은 {@code HOLD_FAILED}로 기록했고, 다시 보내도 결과가 같다.
 * 오류 코드는 {@link AppearanceErrorCode#INSUFFICIENT_BALANCE}(사용자에게 알릴 거절) 또는
 * {@link AppearanceErrorCode#HP_HOLD_FAILED}(그 밖의 거절)다. 보류 재확인 배치는 이 예외로 "결과가 정해짐"을 구분한다.
 */
public class AppearanceHoldRejectedException extends AppearanceException {

    public AppearanceHoldRejectedException(AppearanceErrorCode errorCode) {
        super(errorCode);
    }
}
