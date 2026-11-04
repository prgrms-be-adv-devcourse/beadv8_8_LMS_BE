package com.hapbang.chat.out.member;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.hapbang.shared.member.dto.MemberPageDto;

/**
 * 회원 내부 HTTP API의 chat-app 전용 HTTP Interface({@code docs/api/internal-members.md}). 프록시는 {@link ChatMemberApiConfig}가
 * 만든다(기본 주소·내부 API 키 헤더·제한 시간·오류 변환).
 * <p>
 * 응답은 회원 앱의 성공 봉투(RsData)다. 204처럼 본문이 없을 수 있어 상태와 함께 받는다.
 */
@HttpExchange(ChatMemberInternalApi.MEMBERS_PATH)
public interface ChatMemberInternalApi {

    String MEMBERS_PATH = "/api/v1/internal/members";

    /**
     * 회원 목록 한 페이지(ID 오름차순 키셋, 모든 역할·상태). afterId가 null이면 쿼리에서 빼고 처음부터 읽는다. size는 1~500
     */
    @GetExchange
    ResponseEntity<Envelope> getMembers(@RequestParam("size") int size,
            @RequestParam(name = "afterId", required = false) Long afterId);

    /** 회원 성공 응답(RsData)의 모양. chat-app은 회원 global에 의존하지 않아 같은 필드로 읽는다 */
    record Envelope(Integer statusCode, String message, MemberPageDto data) {
    }
}
