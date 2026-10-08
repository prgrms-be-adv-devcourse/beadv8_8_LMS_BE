package com.hapbang.chat.in;

import java.util.List;
import java.util.Map;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.hapbang.chat.domain.ChatErrorCode;
import com.hapbang.chat.domain.ChatException;

/**
 * 채팅 API 오류를 ProblemDetail(RFC 9457)로 응답한다. 팀 공통 응답 포맷이 정해지면 교체한다.
 * <p>
 * {@code code}에 오류 코드를, 요청 값 검증 실패 시 {@code errors}에 항목별 메시지를 담는다.
 */
// TODO: 팀 공통 응답·예외 포맷이 정해지면 global 모듈로 옮긴다.
@RestControllerAdvice(assignableTypes = ChatRoomController.class)
public class ChatExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ChatException.class)
    public ProblemDetail handleChatException(ChatException exception) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(exception.getErrorCode().getStatus(),
                exception.getMessage());
        problemDetail.setProperty("code", exception.getErrorCode().name());
        return problemDetail;
    }

    // 아래 두 메서드는 ResponseEntityExceptionHandler 재정의라 반환 타입이 ResponseEntity<Object>로 고정되어 있다.

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> fieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(invalidRequest(errors));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> fieldError(parameterName(result.getMethodParameter(), error),
                                error.getDefaultMessage())))
                .toList();
        return ResponseEntity.badRequest().body(invalidRequest(errors));
    }

    private ProblemDetail invalidRequest(List<Map<String, String>> errors) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                ChatErrorCode.INVALID_REQUEST.getMessage());
        problemDetail.setProperty("code", ChatErrorCode.INVALID_REQUEST.name());
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
