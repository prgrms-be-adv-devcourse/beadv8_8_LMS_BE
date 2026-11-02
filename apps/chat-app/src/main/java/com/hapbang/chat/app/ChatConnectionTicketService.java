package com.hapbang.chat.app;

import java.util.Optional;

import com.hapbang.chat.app.dto.ChatConnectionTicket;
import com.hapbang.chat.app.dto.ChatConnectionTicketResponse;
import com.hapbang.shared.member.domain.Role;

/**
 * 웹소켓 연결 티켓(일회용) 발급·사용(설계 문서 6장). 브라우저는 웹소켓 업그레이드 요청에 Authorization을 싣지 못하고 JWT를
 * 주소에 넣는 것은 금지라, Gateway가 검증한 REST 요청으로 짧게 쓰는 티켓을 받아 연결 주소에 싣는다.
 */
public interface ChatConnectionTicketService {

    /**
     * 티켓을 발급한다.
     *
     * @param tokenExpiresAtHeader Gateway가 access token의 {@code exp}로 넣은 값(epoch 초). 없거나 형식이 틀리면 무시한다
     * @throws com.hapbang.chat.app.exception.ChatException 발급 상한을 넘으면 {@code TOO_MANY_TICKET_REQUESTS},
     *                                                      티켓을 저장할 수 없으면 {@code CONNECTION_TICKET_UNAVAILABLE}
     */
    ChatConnectionTicketResponse issue(Long userId, Role role, String tokenExpiresAtHeader);

    /** 티켓을 쓴다(꺼내면서 지운다). 없거나 만료됐거나 이미 썼으면 빈 값. 저장소를 읽지 못해도 빈 값이다 */
    Optional<ChatConnectionTicket> consume(String ticket);
}
