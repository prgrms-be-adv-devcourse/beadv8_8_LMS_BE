package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import com.hapbang.auxiliary.chatbot.config.ChatbotChatProperties;
import com.hapbang.auxiliary.chatbot.exception.ChatbotErrorCode;
import com.hapbang.auxiliary.chatbot.exception.ChatbotException;

/**
 * FE가 보낸 이전 대화 (#720, 11/09 결정: 서버는 대화를 저장하지 않는다).
 * <ul>
 *     <li>최근 {@code history-max-messages}개만 쓴다. 넘으면 앞에서 버린다(오류 아님).</li>
 *     <li>역할은 {@link Role#USER}·{@link Role#ASSISTANT}만이다(그 밖은 요청을 읽을 때 400). 빈 내용, 역할별 최대 길이 초과,
 *     쓰는 대화의 글자 수 합 초과는 400({@link ChatbotErrorCode#INVALID_CHAT_HISTORY}).</li>
 *     <li>Tool 결과·추천 카드는 받지 않는다(필드가 없다). 필요하면 이번 요청에서 Tool을 다시 부른다.</li>
 *     <li>시스템 프롬프트는 서버 것만 쓴다. 사용자가 history를 꾸며도 자기 응답에만 영향이 있다.</li>
 * </ul>
 */
public final class ChatHistory {

    private ChatHistory() {
    }

    /** 대화 하나 */
    public record Entry(Role role, String content) {
    }

    /** 대화의 역할 */
    public enum Role {
        USER,
        ASSISTANT
    }

    /**
     * 검사하고 최근 것만 남겨 LLM 메시지로 바꾼다.
     *
     * @throws ChatbotException 빈 내용·길이 초과
     */
    static List<Message> toMessages(List<Entry> history, ChatbotChatProperties properties) {
        for (Entry entry : history) {
            if (entry == null || entry.role() == null || entry.content() == null || entry.content().isBlank()) {
                throw new ChatbotException(ChatbotErrorCode.INVALID_CHAT_HISTORY);
            }
            int max = entry.role() == Role.USER ? properties.userMaxLength() : properties.assistantMaxLength();
            if (length(entry.content()) > max) {
                throw new ChatbotException(ChatbotErrorCode.INVALID_CHAT_HISTORY);
            }
        }
        List<Entry> recent = history.subList(Math.max(0, history.size() - properties.historyMaxMessages()), history.size());
        int total = 0;
        List<Message> messages = new ArrayList<>();
        for (Entry entry : recent) {
            total += length(entry.content());
            messages.add(entry.role() == Role.USER ? new UserMessage(entry.content()) : new AssistantMessage(entry.content()));
        }
        if (total > properties.historyMaxTotalLength()) {
            throw new ChatbotException(ChatbotErrorCode.INVALID_CHAT_HISTORY);
        }
        return messages;
    }

    /** 쓰는 대화의 글자 수 합(로그용) */
    static int totalLength(List<Entry> history) {
        int total = 0;
        for (Entry entry : history) {
            total += entry.content() == null ? 0 : length(entry.content());
        }
        return total;
    }

    // 글자(코드 포인트) 수. 요청 검사와 같은 기준이다
    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
