package com.hapbang.chat.app.dto;

import java.time.Instant;

/**
 * 열린 웹소켓 연결의 재인증 결과(설계 문서 6장 "재인증"). 실패해도 연결은 닫지 않고 기존 만료까지 둔다.
 *
 * @param outcome             결과
 * @param connectionExpiresAt 이 연결이 닫히는 시각. 성공이면 새 티켓의 연결 만료, 실패면 바뀌지 않은 기존 만료다. 회원을 결합하지
 *                            않았거나 이미 닫힌 연결이면 null
 */
public record ChatReauthResult(Outcome outcome, Instant connectionExpiresAt) {

    public enum Outcome {
        /** 새 티켓의 연결 만료로 바꿨다 */
        REAUTHENTICATED,
        /** 티켓이 없거나, 모양이 틀리거나, 만료됐거나, 이미 썼거나, 읽을 수 없다. 티켓의 연결 만료가 이미 지난 경우도 같다 */
        INVALID_TICKET,
        /** 다른 회원의 티켓이다. 티켓은 쓴 것으로 사라진다 */
        MEMBER_MISMATCH,
        /** 연결이 만료됐거나 닫히는 중이거나 회원을 결합하지 않았다 */
        CONNECTION_CLOSING
    }

    public static ChatReauthResult reauthenticated(Instant connectionExpiresAt) {
        return new ChatReauthResult(Outcome.REAUTHENTICATED, connectionExpiresAt);
    }

    public static ChatReauthResult failed(Outcome outcome, Instant connectionExpiresAt) {
        return new ChatReauthResult(outcome, connectionExpiresAt);
    }

    public boolean succeeded() {
        return outcome == Outcome.REAUTHENTICATED;
    }
}
