package com.hapbang.coupon.app;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * DB 없이 유일 제약 위반 판별을 확인할 때 쓰는 PostgreSQL 오류(강의 {@code PostgresErrorFixture}와 같은 방식). 메시지는 한국어로 둬서
 * 문구가 아니라 구조화된 SQLState·제약 이름 필드로 판별하는지 본다.
 */
final class PostgresUniqueViolation {

    private PostgresUniqueViolation() {
    }

    /** Hibernate 예외 안에 PostgreSQL 드라이버 예외가 들어 있는, 실제와 같은 원인 체인 */
    static DataIntegrityViolationException of(String constraintName) {
        ConstraintViolationException hibernate = new ConstraintViolationException(
                "could not execute statement", postgresError(constraintName), null);
        return new DataIntegrityViolationException("could not execute statement", hibernate);
    }

    private static SQLException postgresError(String constraintName) {
        // 드라이버는 compileOnly라 테스트 컴파일 경로에 없어 실행 경로(test-support runtimeOnly)의 클래스로 만든다.
        // 서버 오류 형식: 필드 타입 문자 + 값 + NUL (S: 심각도, C: SQLState, M: 메시지, n: 제약 이름)
        String fields = "SERROR\0C23505\0M중복된 키 값이 고유 제약 조건을 위반함\0n" + constraintName + "\0";
        try {
            Class<?> serverErrorType = Class.forName("org.postgresql.util.ServerErrorMessage");
            Object serverError = serverErrorType.getConstructor(String.class).newInstance(fields);
            return (SQLException) Class.forName("org.postgresql.util.PSQLException")
                    .getConstructor(serverErrorType).newInstance(serverError);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("PostgreSQL 드라이버 예외를 만들 수 없습니다.", exception);
        }
    }
}
