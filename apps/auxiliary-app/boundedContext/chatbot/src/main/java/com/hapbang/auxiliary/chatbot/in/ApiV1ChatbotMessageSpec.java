package com.hapbang.auxiliary.chatbot.in;

import org.springframework.http.codec.ServerSentEvent;

import com.hapbang.auxiliary.chatbot.app.dto.ChatAnswerResDto;
import com.hapbang.auxiliary.chatbot.in.dto.ChatMessageReqDto;
import com.hapbang.auxiliary.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;

@Tag(name = "챗봇 자유 질문", description = "로그인 회원 (#720). LLM이 FAQ 검색·강의 추천·인플루언서 추천 Tool을 골라 답한다. "
        + "대화 ID·대화 생성 API는 없고, 이전 대화는 FE가 요청마다 history로 보낸다")
public interface ApiV1ChatbotMessageSpec {

    @Operation(summary = "자유 질문(한 번에 받기)", description = """
            추천 결과는 cards(코드가 만든 카드)로 따로 오고 answer는 설명이다. 카드는 cards만 그린다. \
            LLM이 실패하거나 시간 상한을 넘기면 fallback=true다. 이때 추천 카드를 이미 만들었으면 카드와 \
            정해진 문장("추천 결과를 아래에 보여 드려요."), 아니면 FAQ 검색 상위 1건 또는 안내 문구로 답한다. \
            history 규칙(최대 50개, 최근 12개만 씀, role은 USER·ASSISTANT, 내용 USER 1~500자·ASSISTANT 1~2,000자, \
            쓰는 대화의 글자 수 합 8,000자까지, 숫자는 서버 설정 기본값)을 어기면 400(INVALID_CHAT_HISTORY, \
            role 값이 틀리면 INVALID_REQUEST). FAQ 검색·대화와 합쳐 회원마다 주기당 호출 수를 제한한다(넘으면 429).""")
    RsData<ChatAnswerResDto> chat(@Parameter(hidden = true) Long userId, @Valid ChatMessageReqDto request);

    @Operation(summary = "자유 질문(스트림, SSE)", description = """
            text/event-stream. event: token, data: {"text":"답변 조각"} 이 여러 번 온 뒤, 추천 Tool을 불렀으면 \
            event: cards, data: {"lectures":{"basis":"PERSONALIZED","items":[...]},"influencers":{...}} 가 한 번, \
            마지막에 event: done, data: {"sources":[...],"fallback":false} 로 끝난다. 첫 조각 전에 실패하면 정해진 답을 \
            token 한 번 + (cards) + done(fallback=true)으로, 도중에 실패하면 (cards) + event: error, data: {"message":"..."} 로 \
            끝난다. 호출 제한을 넘거나 history가 규칙에 맞지 않으면 스트림을 열지 않고 429·400(ProblemDetail)이다.""")
    Flux<ServerSentEvent<Object>> stream(@Parameter(hidden = true) Long userId, @Valid ChatMessageReqDto request);
}
