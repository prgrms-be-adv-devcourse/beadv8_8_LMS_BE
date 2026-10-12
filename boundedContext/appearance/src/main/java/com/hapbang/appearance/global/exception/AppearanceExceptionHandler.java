package com.hapbang.appearance.global.exception;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.hapbang.appearance.domain.AppearanceException;

/**
 * 출연 API 오류를 Problem Detail(RFC 9457)로 응답한다.
 * <ul>
 *     <li>{@code basePackages}로 출연 모듈의 컨트롤러에만 적용한다. 다른 모듈의 오류 응답에는 끼어들지 않는다.</li>
 *     <li>{@code code}에 {@code AppearanceErrorCode} 이름을 담는다.</li>
 *     <li>Spring MVC 기본 오류(필수 값 누락, 잘못된 형식 등)도 상위 클래스가 Problem Detail로 바꾼다.</li>
 * </ul>
 */
// TODO: 팀 공통 응답·예외 포맷이 정해지면 global 모듈로 옮기거나 맞춘다.
@RestControllerAdvice(basePackages = "com.hapbang.appearance")
public class AppearanceExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AppearanceException.class)
    public ProblemDetail handleAppearanceException(AppearanceException exception) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(exception.getErrorCode().getStatus(),
                exception.getMessage());
        problemDetail.setProperty("code", exception.getErrorCode().name());
        return problemDetail;
    }
}
