package com.hapbang.chat.out.member;

import org.springframework.web.client.RestClient;

/** 컨텍스트 없이 운영 빈 메서드({@link ChatMemberApiConfig#chatMemberInternalApi})로 회원 내부 API 프록시를 만든다 */
public final class ChatMemberApiTestClients {

    private ChatMemberApiTestClients() {
    }

    public static ChatMemberInternalApi create(RestClient.Builder restClientBuilder, ChatMemberApiProperties properties,
            String internalApiKey) {
        return new ChatMemberApiConfig().chatMemberInternalApi(restClientBuilder, properties, internalApiKey);
    }
}
