package com.hapbang.coupon.app.port;

/** 강의 앱 내부 API에 닿지 못했거나 응답을 읽지 못했다 */
public class LectureCatalogUnavailableException extends RuntimeException {

    public LectureCatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
