package com.hapbang.appearance.global.exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;

/**
 * 출연 API 오류를 Problem Detail(RFC 9457)로 응답한다.
 * <ul>
 *     <li>{@code basePackages}로 출연 모듈의 컨트롤러에만 적용한다. 다른 모듈의 오류 응답에는 끼어들지 않는다.</li>
 *     <li>{@code code}에 {@code AppearanceErrorCode} 이름을 담는다.</li>
 *     <li>요청 값 검증 실패와 읽을 수 없는 요청 본문은 400 {@code INVALID_REQUEST}로 응답하고,
 *     검증 실패면 {@code errors}에 항목별 메시지를 담는다 (채팅 모듈과 같은 형식).</li>
 *     <li>그 밖의 Spring MVC 기본 오류도 상위 클래스가 Problem Detail로 바꾼다.</li>
 * </ul>
 */
// TODO: 팀 공통 응답·예외 포맷(Global Exception)이 정해지면 global 모듈로 옮기거나 맞춘다.
@RestControllerAdvice(basePackages = "com.hapbang.appearance")
public class AppearanceExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AppearanceException.class)
    public ProblemDetail handleAppearanceException(AppearanceException exception) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(exception.getErrorCode().getStatus(),
                exception.getMessage());
        problemDetail.setProperty("code", exception.getErrorCode().name());
        return problemDetail;
    }

    /**
     * {@code @PreAuthorize} 역할 검사 거절. 출연 API의 역할 검사는 "인플루언서 이상"뿐이라 {@code NOT_INFLUENCER}로 응답한다.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException exception) {
        return handleAppearanceException(new AppearanceException(AppearanceErrorCode.NOT_INFLUENCER));
    }

    /**
     * 낙관적 락 충돌. 같은 요청서를 동시에 수정·응답해 버전이 맞지 않으면, 나중에 커밋하려던 요청을 409로 응답한다.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException exception) {
        return handleAppearanceException(new AppearanceException(AppearanceErrorCode.CONCURRENT_UPDATE));
    }

    // 아래 메서드들은 ResponseEntityExceptionHandler 재정의라 반환 타입이 ResponseEntity<Object>로 고정되어 있다.

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ObjectError error : exception.getBindingResult().getAllErrors()) {
            String field = null;
            if (error instanceof FieldError fieldError) {
                field = fieldError.getField();
            }
            errors.add(fieldError(field, error.getDefaultMessage()));
        }
        return ResponseEntity.badRequest().body(invalidRequest(errors));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = parameterName(result.getMethodParameter(), error);
                errors.add(fieldError(field, error.getDefaultMessage()));
            }
        }
        return ResponseEntity.badRequest().body(invalidRequest(errors));
    }

    /** JSON 형식 오류, 날짜·숫자 형식 오류 등 요청 본문을 읽을 수 없을 때 */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(invalidRequest(List.of()));
    }

    private ProblemDetail invalidRequest(List<Map<String, String>> errors) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                AppearanceErrorCode.INVALID_REQUEST.getMessage());
        problemDetail.setProperty("code", AppearanceErrorCode.INVALID_REQUEST.name());
        problemDetail.setProperty("errors", errors);
        return problemDetail;
    }

    private String parameterName(MethodParameter parameter, Object error) {
        if (error instanceof FieldError fieldError) {
            return fieldError.getField();
        }
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && !requestParam.name().isEmpty()) {
            return requestParam.name();
        }
        return parameter.getParameterName();
    }

    private Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field == null ? "" : field, "message", message == null ? "" : message);
    }
}
