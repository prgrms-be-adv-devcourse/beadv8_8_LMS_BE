package com.hapbang.auxiliary.chatbot.app;

import java.sql.SQLException;

import org.springframework.dao.DataAccessException;

import com.hapbang.auxiliary.chatbot.domain.MemberEventFailure;
import com.hapbang.auxiliary.chatbot.domain.PersonalInfoScrubber;

import tools.jackson.core.JacksonException;

/**
 * 예외에서 관리자에게 보여 줄 실패 이유를 만든다 (#976). 스택은 남기지 않는다.
 * <ul>
 *     <li>DB 예외: 메시지 대신 {@code SQLState=…}만 남긴다. PostgreSQL 메시지의 {@code Detail: Key (…)=(값)}에 회원 값이 들어간다.
 *     SQLException이 없으면 메시지를 남기지 않는다.</li>
 *     <li>JSON 읽기 예외: 메시지에 받은 원문 일부가 들어가므로 남기지 않는다.</li>
 *     <li>그 밖: 가장 안쪽 원인의 메시지(없으면 바깥 메시지)에서 이메일·전화를 지운다. 길이는 기록이 자른다.</li>
 * </ul>
 */
final class FailureCauseExtractor {

    private FailureCauseExtractor() {
    }

    static MemberEventFailure.Cause extract(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return new MemberEventFailure.Cause(failure.getClass().getName(), root.getClass().getName(), message(failure, root));
    }

    private static String message(Throwable failure, Throwable root) {
        boolean dataAccess = false;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                return "SQLState=" + sql.getSQLState();
            }
            if (current instanceof DataAccessException) {
                dataAccess = true;
            }
            if (current instanceof JacksonException) {
                return null;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        if (dataAccess) {
            return null;
        }
        String message = root.getMessage() != null ? root.getMessage() : failure.getMessage();
        return PersonalInfoScrubber.scrub(message);
    }
}
