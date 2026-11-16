package com.hapbang.auxiliary.chatbot.in;

import org.springframework.http.codec.ServerSentEvent;

import com.hapbang.auxiliary.chatbot.app.dto.FaqAnswer;
import com.hapbang.auxiliary.chatbot.in.dto.FaqChatReqDto;
import com.hapbang.auxiliary.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;

@Tag(name = "챗봇 FAQ 대화", description = "챗봇 FAQ 대화 (로그인 회원)")
public interface ApiV1ChatbotChatSpec {

    @Operation(summary = "FAQ 대화(한 번에 받기)", description = """
            FAQ 검색 결과만 근거로 답한다. sources는 근거 FAQ다. LLM이 실패하거나 시간 상한(chat-timeout)을 넘기면 fallback=true이고 \
            검색 상위 1건의 답변 또는 안내 문구로 답한다. 검색과 합쳐 회원마다 주기당 호출 수를 제한한다(넘으면 429).""")
    RsData<FaqAnswer> chat(@Parameter(hidden = true) Long userId, @Valid FaqChatReqDto request);

    @Operation(summary = "FAQ 대화(스트림, SSE)", description = """
            text/event-stream. event: token, data: {"text":"답변 조각"} 이 여러 번 온 뒤 \
            event: done, data: {"sources":[{"code":"FAQ-501","question":"..."}],"fallback":false} 로 끝난다. \
            첫 조각 전에 실패하면 fallback 답을 token 한 번 + done(fallback=true)으로, 도중에 실패하면 \
            event: error, data: {"message":"..."} 로 끝난다. 호출 제한을 넘으면 스트림을 열지 않고 429(ProblemDetail)다.""")
    Flux<ServerSentEvent<Object>> stream(@Parameter(hidden = true) Long userId, @Valid FaqChatReqDto request);
}
