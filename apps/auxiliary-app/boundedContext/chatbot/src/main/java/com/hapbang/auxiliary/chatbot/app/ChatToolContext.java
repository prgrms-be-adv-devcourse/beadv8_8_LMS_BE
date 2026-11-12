package com.hapbang.auxiliary.chatbot.app;

import java.util.Collection;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 대화가 Tool에 넘기는 {@link ToolContext}의 키와 값 (#720). 챗봇 안에서만 쓰는 키다.
 * <ul>
 *     <li>대화 쪽은 Map을 {@link #of}로만 만든다. Tool마다 키가 다르면 한 Tool이 값을 못 찾아 대화가 실패한다.</li>
 *     <li>ToolContext는 모델에게 보이지 않는다(Tool 인자 스키마에서 빠진다). 그래서 회원 ID는 Tool 인자로 받지 않고 여기서만 꺼낸다.</li>
 *     <li>값이 없으면 호출 쪽 잘못이라 Tool이 {@link IllegalStateException}으로 거절한다.</li>
 * </ul>
 */
public final class ChatToolContext {

    /** 요청 회원 ID. 값은 {@link Long} */
    public static final String MEMBER_ID = "memberId";

    /** Tool이 찾은 FAQ를 담는 목록. 값은 스레드 안전한 {@code Collection<FaqHit>}이어야 한다(스트림은 다른 스레드에서 Tool을 부른다) */
    public static final String FAQ_SOURCES = "faqSources";

    private ChatToolContext() {
    }

    /** 대화 한 번의 ToolContext */
    public static Map<String, Object> of(long memberId, Collection<FaqHit> faqSources) {
        return Map.of(MEMBER_ID, memberId, FAQ_SOURCES, faqSources);
    }

    /** @param tool 거절 메시지에 넣을 Tool 이름 */
    static long memberId(@Nullable ToolContext toolContext, String tool) {
        if (toolContext != null && toolContext.getContext().get(MEMBER_ID) instanceof Long memberId) {
            return memberId;
        }
        throw new IllegalStateException("ToolContext에 " + MEMBER_ID + "가 없어 " + tool + " 호출을 거절한다");
    }

    @SuppressWarnings("unchecked")
    static Collection<FaqHit> faqSources(@Nullable ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext().get(FAQ_SOURCES) instanceof Collection<?> collection) {
            return (Collection<FaqHit>) collection;
        }
        throw new IllegalStateException("ToolContext에 " + FAQ_SOURCES + " 목록이 없어 searchFaq 호출을 거절한다");
    }
}
