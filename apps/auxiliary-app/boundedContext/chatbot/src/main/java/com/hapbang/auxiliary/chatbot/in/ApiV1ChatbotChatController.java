package com.hapbang.auxiliary.chatbot.in;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.chatbot.app.ChatbotFaqFacade;
import com.hapbang.auxiliary.chatbot.app.dto.FaqAnswer;
import com.hapbang.auxiliary.chatbot.app.dto.FaqStreamEvent;
import com.hapbang.auxiliary.chatbot.in.dto.FaqChatReqDto;
import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

/**
 * FAQ 대화(#715). 명세는 {@link ApiV1ChatbotChatSpec}에 있다. 대화 기억 없이 질문 하나에 답한다. 메뉴·추천이 붙은 대화는 #720이다.
 * <p>
 * 스트림은 SSE다. 이벤트 {@code token}({@code {"text"}}) 0번 이상 → {@code done}({@code {"sources", "fallback"}}) 한 번,
 * 도중 실패는 {@code error}({@code {"message"}})로 끝난다. 시간 상한은 서버 {@code hapbang.chatbot.faq.stream-timeout}이 Gateway 스트림 route
 * (k8s/base/gateway.yaml)보다 짧게 둔다.
 */
@RestController
@RequestMapping("/api/v1/chatbot/chat/faq")
@RequiredArgsConstructor
public class ApiV1ChatbotChatController implements ApiV1ChatbotChatSpec {

    private final ChatbotFaqFacade facade;

    @Override
    @PostMapping
    public RsData<FaqAnswer> chat(@LoginUserId Long userId, @RequestBody FaqChatReqDto request) {
        return RsData.ok("FAQ 대화 답변입니다.", facade.chat(userId, request.message()));
    }

    @Override
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(@LoginUserId Long userId, @RequestBody FaqChatReqDto request) {
        return facade.stream(userId, request.message()).map(ApiV1ChatbotChatController::toSse);
    }

    // 이벤트 record를 그대로 data(JSON)로 쓴다. record라 필드 순서가 정해지고, 이벤트 이름은 JSON에 넣지 않는다
    private static ServerSentEvent<Object> toSse(FaqStreamEvent event) {
        return ServerSentEvent.<Object>builder(event).event(event.eventName()).build();
    }
}
