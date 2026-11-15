package com.hapbang.auxiliary.notification.app.exception;

import com.hapbang.auxiliary.global.exception.BusinessException;

/** 알림 모듈의 예상된 업무 실패 */
public class NotificationException extends BusinessException {

    public NotificationException(NotificationErrorCode errorCode) {
        super(errorCode);
    }
}
