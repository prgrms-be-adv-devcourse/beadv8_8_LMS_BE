package com.hapbang.auxiliary.chatbot.app;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

/** 챗봇 기능(FAQ 대화·자유 질문·리랭커·쿼리 확장)의 {@link ChatClient}를 만드는 곳 (#835) */
final class ChatbotChatClients {

    private ChatbotChatClients() {
    }

    /**
     * 채팅 모델이 있을 때만 만든다. {@code ChatClient.Builder} 빈 정의는 모델이 없어도 있고, 그때 꺼내면 예외다.
     *
     * @param systemPrompt 기본 시스템 프롬프트. 없으면 null
     * @return 모델이 없으면 null(각 기능이 LLM 없이 답한다)
     */
    static @Nullable ChatClient create(ObjectProvider<ChatModel> chatModel, ObjectProvider<ChatClient.Builder> builder,
            @Nullable String systemPrompt) {
        if (chatModel.getIfAvailable() == null) {
            return null;
        }
        ChatClient.Builder client = builder.getObject();
        if (systemPrompt != null) {
            client.defaultSystem(systemPrompt);
        }
        return client.build();
    }
}
