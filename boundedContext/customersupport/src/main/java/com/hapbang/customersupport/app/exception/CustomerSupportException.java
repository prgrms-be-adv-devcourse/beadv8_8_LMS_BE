package com.hapbang.customersupport.app.exception;

import com.hapbang.global.exception.BusinessException;

/** 고객센터 업무 예외. UseCase·Supporter에서만 던진다(도메인은 {@code Assert.state()}로 불변식만 지킨다) */
public class CustomerSupportException extends BusinessException {

    public CustomerSupportException(CustomerSupportErrorCode errorCode) {
        super(errorCode);
    }

    public CustomerSupportException(CustomerSupportErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}
