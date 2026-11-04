package com.hapbang.chat.app;

import java.time.Duration;
import java.util.Optional;

import com.hapbang.chat.app.dto.ChatConnectionTicket;

/**
 * 웹소켓 연결 티켓(일회용) 저장소(설계 문서 6장). 티켓 원문을 저장 키로 쓰지 않는 것, 꺼내면서 지우는 원자성, 만료는 구현이 맡는다.
 * Redis가 없는 실행(실시간 전달을 끈 테스트)에는 빈이 없다. 실패는 예외로 알린다.
 */
public interface ChatConnectionTicketStore {

    /** 티켓을 {@code ttl} 동안 저장한다 */
    void save(String ticket, ChatConnectionTicket value, Duration ttl);

    /** 티켓을 꺼내고 같은 명령으로 지운다. 없거나 만료됐으면 빈 값. 같은 티켓을 두 번 꺼낼 수 없다 */
    Optional<ChatConnectionTicket> consume(String ticket);

    /**
     * 그 회원의 티켓 발급 횟수를 1 올리고 올린 값을 돌려준다. 창의 첫 발급이 창 길이만큼의 만료를 건다(고정 창).
     */
    long incrementIssueCount(Long userId, Duration window);
}
