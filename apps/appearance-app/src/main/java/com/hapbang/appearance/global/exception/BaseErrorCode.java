package com.hapbang.appearance.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드 공통 계약. 각 모듈이 자기 오류 코드 enum({@code ChatErrorCode}, {@code LectureErrorCode} 등)으로 구현하고,
 * 공통 핸들러가 쓰는 코드는 {@link GlobalErrorCode}가 구현한다.
 * <ul>
 *     <li>{@link #getStatus()}: 응답 HTTP 상태</li>
 *     <li>{@link #getCode()}: 응답의 {@code code}. 프론트가 분기하는 식별자다. 기본값은 enum 이름</li>
 *     <li>{@link #getMessage()}: 응답의 {@code detail} 기본값</li>
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
}
