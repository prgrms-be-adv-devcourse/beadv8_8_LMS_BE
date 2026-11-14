package com.hapbang.auxiliary.chatbot.in;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.chatbot.app.ChatStreamEvent;
import com.hapbang.auxiliary.chatbot.app.ChatbotChatFacade;
import com.hapbang.auxiliary.chatbot.app.dto.ChatAnswerResDto;
import com.hapbang.auxiliary.chatbot.in.dto.ChatMessageReqDto;
import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

/**
 * 챗봇 자유 질문 (#720). 명세는 {@link ApiV1ChatbotMessageSpec}에 있다. 스트림 route(Gateway 60s)는
 * {@code /api/v1/chatbot/**}{@code /stream} 규칙에 들어간다.
 */
@RestController
@RequestMapping("/api/v1/chatbot/messages")
@RequiredArgsConstructor
public class ApiV1ChatbotMessageController implements ApiV1ChatbotMessageSpec {

    private final ChatbotChatFacade facade;

    @Override
    @PostMapping
    public RsData<ChatAnswerResDto> chat(@LoginUserId Long userId, @RequestBody ChatMessageReqDto request) {
        return RsData.ok("자유 질문 답변입니다.", facade.chat(userId, request.message(), request.historyOrEmpty()));
    }

    @Override
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(@LoginUserId Long userId, @RequestBody ChatMessageReqDto request) {
        return facade.stream(userId, request.message(), request.historyOrEmpty())
                .map(ApiV1ChatbotMessageController::toSse);
    }

    // 이벤트 record를 그대로 data(JSON)로 쓴다. 카드 이벤트는 카드 객체만 data로 보낸다. 이벤트 이름은 JSON에 넣지 않는다
    private static ServerSentEvent<Object> toSse(ChatStreamEvent event) {
        Object data = event instanceof ChatStreamEvent.Cards cards ? cards.cards() : event;
        return ServerSentEvent.<Object>builder(data).event(event.eventName()).build();
    }
}
