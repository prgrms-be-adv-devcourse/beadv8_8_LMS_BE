package com.hapbang.auxiliary.chatbot.in;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.chatbot.app.ChatbotFaqFacade;
import com.hapbang.auxiliary.chatbot.app.FaqAnswer;
import com.hapbang.auxiliary.chatbot.app.FaqStreamEvent;
import com.hapbang.auxiliary.chatbot.in.dto.FaqChatRequest;
import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

/**
 * FAQ 대화(#715). 대화 기억 없이 질문 하나에 답한다. 메뉴·추천이 붙은 대화는 #720이다.
 * <p>
 * 스트림은 SSE다. 이벤트 {@code token}({@code {"text"}}) 0번 이상 → {@code done}({@code {"sources", "fallback"}}) 한 번,
 * 도중 실패는 {@code error}({@code {"message"}})로 끝난다. 시간 상한은 서버 {@code hapbang.chatbot.faq.stream-timeout}이 Gateway 스트림 route
 * (k8s/base/gateway.yaml)보다 짧게 둔다.
 */
@Tag(name = "Chatbot FAQ Chat", description = "챗봇 FAQ 대화 (로그인 회원)")
@RestController
@RequestMapping("/api/v1/chatbot/chat/faq")
@RequiredArgsConstructor
public class ApiV1ChatbotChatController {

    private final ChatbotFaqFacade facade;

    @Operation(summary = "FAQ 대화(한 번에 받기)", description = """
            FAQ 검색 결과만 근거로 답한다. sources는 근거 FAQ다. LLM이 실패하거나 시간 상한(chat-timeout)을 넘기면 fallback=true이고 \
            검색 상위 1건의 답변 또는 안내 문구로 답한다. 검색과 합쳐 회원마다 주기당 호출 수를 제한한다(넘으면 429).""")
    @PostMapping
    public RsData<FaqAnswer> chat(@LoginUserId Long userId, @Valid @RequestBody FaqChatRequest request) {
        return RsData.ok("FAQ 대화 답변입니다.", facade.chat(userId, request.message()));
    }

    @Operation(summary = "FAQ 대화(스트림, SSE)", description = """
            text/event-stream. event: token, data: {"text":"답변 조각"} 이 여러 번 온 뒤 \
            event: done, data: {"sources":[{"code":"FAQ-501","question":"..."}],"fallback":false} 로 끝난다. \
            첫 조각 전에 실패하면 fallback 답을 token 한 번 + done(fallback=true)으로, 도중에 실패하면 \
            event: error, data: {"message":"..."} 로 끝난다. 호출 제한을 넘으면 스트림을 열지 않고 429(ProblemDetail)다.""")
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(@LoginUserId Long userId, @Valid @RequestBody FaqChatRequest request) {
        return facade.stream(userId, request.message()).map(ApiV1ChatbotChatController::toSse);
    }

    // 이벤트 record를 그대로 data(JSON)로 쓴다. record라 필드 순서가 정해지고, 이벤트 이름은 JSON에 넣지 않는다
    private static ServerSentEvent<Object> toSse(FaqStreamEvent event) {
        return ServerSentEvent.<Object>builder(event).event(event.eventName()).build();
    }
}
