package com.hapbang.auxiliary.chatbot.in;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.chatbot.app.ChatAnswer;
import com.hapbang.auxiliary.chatbot.app.ChatStreamEvent;
import com.hapbang.auxiliary.chatbot.app.ChatbotChatFacade;
import com.hapbang.auxiliary.chatbot.in.dto.ChatMessageRequest;
import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

/**
 * 챗봇 자유 질문 (#720). LLM이 FAQ 검색·강의 추천·인플루언서 추천 Tool을 골라 답한다. 대화 ID·대화 생성 API는 없다(11/09 결정).
 * <p>
 * 스트림은 SSE다. 이벤트 {@code token}({@code {"text"}}) 0번 이상 → 추천 Tool을 불렀으면 {@code cards} 한 번 →
 * {@code done}({@code {"sources", "fallback"}}) 한 번. 도중 실패는 카드가 있으면 {@code cards} 뒤 {@code error}({@code {"message"}})로
 * 끝난다. 스트림 route(Gateway 60s)는 {@code /api/v1/chatbot/**}{@code /stream} 규칙에 들어간다.
 */
@Tag(name = "Chatbot Messages", description = "챗봇 자유 질문 (로그인 회원, #720)")
@RestController
@RequestMapping("/api/v1/chatbot/messages")
@RequiredArgsConstructor
public class ApiV1ChatbotMessageController {

    private final ChatbotChatFacade facade;

    @Operation(summary = "자유 질문(한 번에 받기)", description = """
            LLM이 FAQ 검색·강의 추천·인플루언서 추천 Tool을 골라 답한다. 추천 결과는 cards(코드가 만든 카드)로 따로 오고, \
            answer는 설명이다. LLM이 실패하거나 시간 상한을 넘기면 fallback=true다. 이때 추천 카드를 이미 만들었으면 카드와 \
            정해진 문장("추천 결과를 아래에 보여 드려요."), 아니면 FAQ 검색 상위 1건 또는 안내 문구로 답한다. \
            history 규칙을 어기면 400(INVALID_CHAT_HISTORY 또는 INVALID_REQUEST). FAQ 검색·대화와 합쳐 회원마다 주기당 호출 수를 \
            제한한다(넘으면 429).""")
    @PostMapping
    public RsData<ChatAnswer> chat(@LoginUserId Long userId, @Valid @RequestBody ChatMessageRequest request) {
        return RsData.ok("자유 질문 답변입니다.", facade.chat(userId, request.message(), request.historyOrEmpty()));
    }

    @Operation(summary = "자유 질문(스트림, SSE)", description = """
            text/event-stream. event: token, data: {"text":"답변 조각"} 이 여러 번 온 뒤, 추천 Tool을 불렀으면 \
            event: cards, data: {"lectures":{"basis":"PERSONALIZED","items":[...]},"influencers":{...}} 가 한 번, \
            마지막에 event: done, data: {"sources":[...],"fallback":false} 로 끝난다. 첫 조각 전에 실패하면 정해진 답을 \
            token 한 번 + (카드) + done(fallback=true)으로, 도중에 실패하면 (카드) + event: error, data: {"message":"..."} 로 끝난다. \
            호출 제한을 넘거나 history가 규칙에 맞지 않으면 스트림을 열지 않고 429·400(ProblemDetail)이다.""")
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(@LoginUserId Long userId, @Valid @RequestBody ChatMessageRequest request) {
        return facade.stream(userId, request.message(), request.historyOrEmpty())
                .map(ApiV1ChatbotMessageController::toSse);
    }

    // 이벤트 record를 그대로 data(JSON)로 쓴다. 이벤트 이름은 JSON에 넣지 않는다
    private static ServerSentEvent<Object> toSse(ChatStreamEvent event) {
        Object data = event instanceof ChatStreamEvent.Cards cards ? cards.cards() : event;
        return ServerSentEvent.<Object>builder(data).event(event.eventName()).build();
    }
}
