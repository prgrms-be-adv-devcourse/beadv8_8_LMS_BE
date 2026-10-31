package com.hapbang.member.global.exception;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * 모든 컨트롤러의 오류를 ProblemDetail(RFC 9457)로 응답하는 유일한 핸들러 (2026-10-12 회의). 모듈은 핸들러를 두지 않는다.
 * 모든 응답에 {@code code}가 있다.
 * <ul>
 *     <li>{@link BusinessException} → 오류 코드의 상태, detail, {@code code}({@link BaseErrorCode#getCode()}), 예외의 추가 속성</li>
 *     <li>요청 값 검증·형식 오류 → 400 {@code INVALID_REQUEST}, {@code errors: [{field, message}]}</li>
 *     <li>컨트롤러 안의 권한 거절({@code @PreAuthorize}) → 로그인했으면 403 {@code FORBIDDEN}, 아니면 401 {@code UNAUTHORIZED}</li>
 *     <li>그 밖의 Spring MVC 예외(405, 415, 없는 경로 404 등) → {@link ResponseEntityExceptionHandler} 기본 응답 + 상태에 맞는 {@code code}</li>
 *     <li>처리하지 못한 예외 → 500 {@code INTERNAL_ERROR}. 내부 메시지는 응답에 노출하지 않고 로그에만 남긴다</li>
 * </ul>
 * Security 필터에서 막힌 요청(로그인 안 함, {@code /admin/**} URL 권한)은 컨트롤러에 오지 않으므로 이 핸들러가 아니라
 * {@code SecurityConfig}가 응답한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final AuthenticationTrustResolver authenticationTrustResolver = new AuthenticationTrustResolverImpl();

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusinessException(BusinessException exception) {
        BaseErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getStatus().is5xxServerError()) {
            log.error("업무 예외 code={}, message={}", errorCode.getCode(), exception.getMessage(), exception);
        } else {
            log.warn("업무 예외 code={}, message={}", errorCode.getCode(), exception.getMessage());
        }
        ProblemDetail problemDetail = problem(errorCode, exception.getMessage());
        exception.getProperties().forEach(problemDetail::setProperty);
        return problemDetail;
    }

    /** 컨트롤러·서비스의 {@code @PreAuthorize} 거절. 비로그인 사용자면 403이 아니라 401로 응답한다 */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException exception) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authenticationTrustResolver.isAnonymous(authentication)) {
            return problem(GlobalErrorCode.UNAUTHORIZED, GlobalErrorCode.UNAUTHORIZED.getMessage());
        }
        return problem(GlobalErrorCode.FORBIDDEN, GlobalErrorCode.FORBIDDEN.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException exception) {
        return problem(GlobalErrorCode.UNAUTHORIZED, GlobalErrorCode.UNAUTHORIZED.getMessage());
    }

    /** 처리하지 못한 예외. 같은 클래스의 더 구체적인 핸들러(위·{@link ResponseEntityExceptionHandler})가 먼저 선택된다 */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        log.error("처리하지 못한 예외", exception);
        return problem(GlobalErrorCode.INTERNAL_ERROR, GlobalErrorCode.INTERNAL_ERROR.getMessage());
    }

    // 아래 메서드는 ResponseEntityExceptionHandler 재정의라 반환 타입이 ResponseEntity<Object>로 고정되어 있다. 본문은 ProblemDetail이다.

    /** {@code @Valid @RequestBody} 검증 실패 */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> fieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return invalidRequest(GlobalErrorCode.INVALID_REQUEST.getMessage(), errors);
    }

    /** {@code @RequestParam}·{@code @PathVariable}의 제약(@Min 등) 검증 실패 */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> fieldError(parameterName(result.getMethodParameter(), error),
                                error.getDefaultMessage())))
                .toList();
        return invalidRequest(GlobalErrorCode.INVALID_REQUEST.getMessage(), errors);
    }

    /** 요청 파라미터·경로 값의 타입 불일치 (예: 숫자 자리에 문자, 없는 enum 값) */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String field = exception instanceof MethodArgumentTypeMismatchException argumentMismatch
                ? argumentMismatch.getName()
                : exception.getPropertyName();
        Class<?> requiredType = exception.getRequiredType();
        String message = "'%s' 값 '%s'이(가) 올바른 형식(%s)이 아닙니다.%s".formatted(field, exception.getValue(),
                typeName(requiredType), allowedValues(requiredType));
        return invalidRequest(message, List.of(fieldError(field, message)));
    }

    /** 필수 요청 파라미터 누락 */
    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        String field = exception.getParameterName();
        String message = "필수 요청 파라미터 '%s'(%s)가 없습니다.".formatted(field, exception.getParameterType());
        return invalidRequest(message, List.of(fieldError(field, message)));
    }

    /** 요청 본문(JSON)을 읽을 수 없음: 문법 오류, 타입 불일치, 없는 enum 값 등. Boot 4는 Jackson 3(tools.jackson)으로 읽는다 */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        JacksonException cause = findCause(exception, JacksonException.class);
        if (cause == null) {
            return invalidRequest("요청 본문을 읽을 수 없습니다. JSON 형식을 확인해 주세요.", List.of());
        }
        String field = fieldPath(cause.getPath());
        if (field.isEmpty()) {
            return invalidRequest("요청 본문의 JSON 형식이 올바르지 않습니다.", List.of());
        }
        String message;
        if (cause instanceof InvalidFormatException invalidFormat) {
            Class<?> targetType = invalidFormat.getTargetType();
            message = "'%s' 값 '%s'이(가) 올바른 형식(%s)이 아닙니다.%s".formatted(field, invalidFormat.getValue(),
                    typeName(targetType), allowedValues(targetType));
        } else if (cause instanceof MismatchedInputException mismatchedInput) {
            message = "'%s' 값의 형식이 올바르지 않습니다. 기대 형식: %s".formatted(field,
                    typeName(mismatchedInput.getTargetType()));
        } else {
            message = "'%s' 값을 읽을 수 없습니다.".formatted(field);
        }
        return invalidRequest(message, List.of(fieldError(field, message)));
    }

    /**
     * {@link ResponseEntityExceptionHandler}가 기본 처리하는 예외(405, 415, 없는 경로 404 등)의 응답에도 {@code code}를 붙인다.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problemDetail
                && (problemDetail.getProperties() == null || !problemDetail.getProperties().containsKey("code"))) {
            problemDetail.setProperty("code", codeOf(statusCode));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ProblemDetail problem(BaseErrorCode errorCode, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(errorCode.getStatus(), detail);
        problemDetail.setProperty("code", errorCode.getCode());
        return problemDetail;
    }

    private ResponseEntity<Object> invalidRequest(String detail, List<Map<String, String>> errors) {
        ProblemDetail problemDetail = problem(GlobalErrorCode.INVALID_REQUEST, detail);
        problemDetail.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problemDetail);
    }

    /** 공통 코드가 있는 상태는 공통 코드로, 나머지는 HTTP 상태 이름으로 (예: METHOD_NOT_ALLOWED) */
    private String codeOf(HttpStatusCode statusCode) {
        return Arrays.stream(GlobalErrorCode.values())
                .filter(errorCode -> errorCode.getStatus().value() == statusCode.value())
                .findFirst()
                .map(GlobalErrorCode::getCode)
                .orElseGet(() -> {
                    HttpStatus status = HttpStatus.resolve(statusCode.value());
                    return status != null ? status.name() : String.valueOf(statusCode.value());
                });
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

    /** JSON 경로를 {@code contents[0].title} 형태로 만든다 */
    private String fieldPath(List<JacksonException.Reference> path) {
        StringBuilder builder = new StringBuilder();
        for (JacksonException.Reference reference : path) {
            if (reference.getPropertyName() != null) {
                if (!builder.isEmpty()) {
                    builder.append('.');
                }
                builder.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                builder.append('[').append(reference.getIndex()).append(']');
            }
        }
        return builder.toString();
    }

    private String typeName(Class<?> type) {
        return type == null ? "알 수 없음" : type.getSimpleName();
    }

    /** enum이면 사용할 수 있는 값을 알려 준다 */
    private String allowedValues(Class<?> type) {
        if (type == null || !type.isEnum()) {
            return "";
        }
        return " 사용 가능한 값: " + Arrays.stream(type.getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private <T extends Throwable> T findCause(Throwable exception, Class<T> type) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }
}
