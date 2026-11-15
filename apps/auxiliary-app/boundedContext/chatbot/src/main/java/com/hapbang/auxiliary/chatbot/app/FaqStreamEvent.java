package com.hapbang.auxiliary.chatbot.app;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * FAQ 스트림 대화의 이벤트. SSE 이벤트 이름은 {@link #eventName()}, data는 record의 필드(JSON)다.
 * <ul>
 *     <li>{@code token}: 답변 조각. 0번 이상</li>
 *     <li>{@code done}: 끝. 근거 FAQ와 fallback 여부. 정상 종료면 마지막에 한 번</li>
 *     <li>{@code error}: 답변 도중 실패. 이미 보낸 조각은 그대로 두고 끝낸다(첫 조각 전 실패는 fallback 답을 {@code token} + {@code done}으로 보낸다)</li>
 * </ul>
 */
public sealed interface FaqStreamEvent {

    // getter 모양(get·is)이 아니어서 JSON에 들어가지 않지만, 확실히 빼려고 @JsonIgnore를 붙인다
    @JsonIgnore
    String eventName();

    /** 답변 조각. SSE에서 앞 공백이 사라지지 않게 JSON 객체({@code {"text": ...}})로 보낸다 */
    record Token(String text) implements FaqStreamEvent {
        @Override
        public String eventName() {
            return "token";
        }
    }

    record Done(List<FaqAnswer.FaqSource> sources, boolean fallback) implements FaqStreamEvent {
        @Override
        public String eventName() {
            return "done";
        }
    }

    record Error(String message) implements FaqStreamEvent {
        @Override
        public String eventName() {
            return "error";
        }
    }
}
