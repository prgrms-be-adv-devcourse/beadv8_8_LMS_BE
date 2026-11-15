package com.hapbang.auxiliary.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드 공통 계약. 각 모듈이 자기 오류 코드 enum(예: {@code ChatbotErrorCode})으로 구현하고,
 * 공통 핸들러가 쓰는 코드는 {@link GlobalErrorCode}가 구현한다.
 * <ul>
 *     <li>{@link #getStatus()}: 응답 HTTP 상태</li>
 *     <li>{@link #getCode()}: 응답의 {@code code}. 프론트가 분기하는 식별자다. 기본값은 enum 이름</li>
 *     <li>{@link #getMessage()}: 응답의 {@code detail} 기본값</li>
 *     <li>{@link #isRetryLater()}: 잠시 뒤 다시 보내면 되는 거절인지. 기본 false</li>
 * </ul>
 * enum에 Lombok {@code @Getter}를 붙이면 getter와 enum의 {@code name()}이 그대로 구현이 된다.
 */
public interface BaseErrorCode {

    HttpStatus getStatus();

    String getMessage();

    String name();

    default String getCode() {
        return name();
    }

    /**
     * 잠시 뒤 다시 보내면 되는 거절인지(외부 의존이 잠시 안 되는 503 등). 장애 동안 요청마다 몰리고 의존이 회복되면 저절로 풀리므로,
     * 공통 핸들러는 5xx여도 ERROR가 아니라 DEBUG만 남긴다(docs/dev/logging.md). true로 두는 쪽은 상태가 바뀔 때만 로그를 남기고
     * (실패가 시작될 때 WARN, 회복될 때 INFO) 지표로 건수를 본다.
     */
    default boolean isRetryLater() {
        return false;
    }
}
