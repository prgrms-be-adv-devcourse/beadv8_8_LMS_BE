package com.hapbang.coupon.app;

import java.util.Optional;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 무결성 위반이 어느 유일 제약에 걸린 것인지 판별한다(강의 모듈 {@code UniqueIndexViolation}과 같은 방식. 쿠폰은 강의 코드를 쓸 수
 * 없어 같은 모양으로 둔다). 예상한 제약만 업무 결과로 바꾸고, 그 밖의 무결성 위반은 그대로 던진다.
 * <p>
 * PostgreSQL이 보낸 구조화된 SQLState·제약 이름 필드를 쓴다. Hibernate의 {@code getConstraintName()}은 오류 메시지의 영어 문구에서
 * 이름을 뽑아 서버 메시지 언어(lc_messages)가 다르면 비어 있다.
 */
final class UniqueIndexViolation {

    private static final String UNIQUE_VIOLATION = "23505";

    private UniqueIndexViolation() {
    }

    /** 원인의 PostgreSQL 오류가 UNIQUE 위반이면 위반한 제약 이름. 아니면 빈 값 */
    static Optional<String> constraintOf(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgresException) {
                ServerErrorMessage serverError = postgresException.getServerErrorMessage();
                if (serverError == null || !UNIQUE_VIOLATION.equals(serverError.getSQLState())) {
                    return Optional.empty();
                }
                return Optional.ofNullable(serverError.getConstraint());
            }
        }
        return Optional.empty();
    }
}
