package com.hapbang.member.app;

import java.util.Set;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;

/**
 * 회원(users) 유일 위반을 업무 오류(409)로 바꾼다 (#646). 확인과 저장 사이에 다른 요청이 같은 이메일·닉네임을 먼저 저장한 경우다.
 * 그 밖의 무결성 위반은 예상하지 못한 실패라 그대로 돌려준다(공통 핸들러가 500으로 응답).
 * <p>
 * 판별은 팀 방식(D48, docs/dev/persistence.md)대로 PostgreSQL이 보낸 SQLState(23505)와 제약 이름 필드로 한다.
 * 오류 메시지 문구는 서버 언어(lc_messages)에 따라 달라 쓰지 않는다(강의 UniqueIndexViolation과 같다)
 */
final class UserUniqueViolation {

    /** users.email 유일 제약 (V202610121119) */
    static final String EMAIL_UNIQUE = "uc_users_email";
    /** users lower(nickname) 유일 인덱스 (V202611040948) */
    static final String NICKNAME_UNIQUE = "uk_users_nickname_lower";

    private static final String UNIQUE_VIOLATION = "23505";

    private UserUniqueViolation() {
    }

    /** 이메일 위반이면 DUPLICATE_EMAIL, 닉네임 위반이면 DUPLICATE_NICKNAME, 아니면 받은 예외 그대로 */
    static RuntimeException translate(DataIntegrityViolationException exception) {
        if (isOf(exception, EMAIL_UNIQUE)) {
            return new MemberException(MemberErrorCode.DUPLICATE_EMAIL, exception);
        }
        if (isOf(exception, NICKNAME_UNIQUE)) {
            return new MemberException(MemberErrorCode.DUPLICATE_NICKNAME, exception);
        }
        return exception;
    }

    /** 예외 원인의 PostgreSQL 오류가 UNIQUE 위반이고, 위반한 제약이 {@code names} 중 하나인지 */
    private static boolean isOf(DataIntegrityViolationException exception, String... names) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgresException) {
                ServerErrorMessage serverError = postgresException.getServerErrorMessage();
                return serverError != null
                        && UNIQUE_VIOLATION.equals(serverError.getSQLState())
                        && serverError.getConstraint() != null
                        && Set.of(names).contains(serverError.getConstraint());
            }
        }
        return false;
    }
}
