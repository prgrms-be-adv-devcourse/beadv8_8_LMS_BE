package com.hapbang.lecture.app;

import java.util.Set;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 동시 요청이 부분 유일 인덱스에 걸렸는지 판별한다. 이런 위반만 업무 오류(409)로 바꾸고, 그 밖의 무결성 위반은 예상하지 못한
 * 실패라 그대로 던진다(공통 핸들러가 500으로 응답, AGENTS.md 7장).
 * <p>
 * PostgreSQL이 보낸 구조화된 SQLState·제약 이름 필드를 쓴다. Hibernate의 {@code getConstraintName()}은 오류 메시지에서
 * 영어 문구({@code constraint "…"})로 이름을 뽑아 서버 메시지 언어(lc_messages)가 다르면 null이 된다 (billing
 * {@code WalletCreateUseCase}와 같은 방식).
 */
final class UniqueIndexViolation {

    private static final String UNIQUE_VIOLATION = "23505";

    private UniqueIndexViolation() {
    }

    /** 예외 원인의 PostgreSQL 오류가 UNIQUE 위반이고, 위반한 제약이 {@code indexNames} 중 하나인지 */
    static boolean isOf(DataIntegrityViolationException exception, String... indexNames) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgresException) {
                ServerErrorMessage serverError = postgresException.getServerErrorMessage();
                return serverError != null
                        && UNIQUE_VIOLATION.equals(serverError.getSQLState())
                        && serverError.getConstraint() != null
                        && Set.of(indexNames).contains(serverError.getConstraint());
            }
        }
        return false;
    }
}
