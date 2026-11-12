package com.hapbang.auxiliary.chatbot.app;

import com.hapbang.auxiliary.chatbot.app.dto.ChatCardsResDto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 자유 질문 스트림의 이벤트 (#720). SSE 이벤트 이름은 {@link #eventName()}, data는 record의 필드(JSON)다.
 * <ul>
 *     <li>{@code token}: 답변 조각. 0번 이상</li>
 *     <li>{@code cards}: 추천 Tool이 만든 카드. 추천 Tool을 불렀을 때만, 글이 끝난 뒤(또는 실패 직전) 한 번</li>
 *     <li>{@code done}: 끝. 근거 FAQ와 fallback 여부. 정상 종료면 마지막에 한 번</li>
 *     <li>{@code error}: 답변 도중 실패. 이미 보낸 조각은 그대로 두고, 카드가 있으면 {@code cards}를 보낸 뒤 끝낸다</li>
 * </ul>
 */
public sealed interface ChatStreamEvent {

    @JsonIgnore
    String eventName();

    /** 답변 조각. SSE에서 앞 공백이 사라지지 않게 JSON 객체({@code {"text": ...}})로 보낸다 */
    record Token(String text) implements ChatStreamEvent {
        @Override
        public String eventName() {
            return "token";
        }
    }

    record Cards(ChatCardsResDto cards) implements ChatStreamEvent {
        @Override
        public String eventName() {
            return "cards";
        }
    }

    record Done(List<FaqAnswer.FaqSource> sources, boolean fallback) implements ChatStreamEvent {
        @Override
        public String eventName() {
            return "done";
        }
    }

    record Error(String message) implements ChatStreamEvent {
        @Override
        public String eventName() {
            return "error";
        }
    }
}
