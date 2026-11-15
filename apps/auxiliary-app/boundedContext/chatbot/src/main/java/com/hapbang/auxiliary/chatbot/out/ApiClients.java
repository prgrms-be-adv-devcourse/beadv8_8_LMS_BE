package com.hapbang.auxiliary.chatbot.out;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 내부 API Client를 꺼낸다 (#717). Client는 {@code hapbang.clients.<앱>.base-url}이 있을 때만 생긴다. 운영 설정은 주소가 없으면
 * 기동하지 않으므로(application.yaml) Client가 없는 것은 주소 없이 띄운 모듈 테스트뿐이다. 그때 부르면 예외라 싱크 회차가 실패로 남는다.
 */
final class ApiClients {

    private ApiClients() {
    }

    /** @param app 설정 키의 앱 이름(member, lecture, appearance) */
    static <T> T require(ObjectProvider<T> client, String app) {
        T found = client.getIfAvailable();
        if (found == null) {
            throw new IllegalStateException(
                    "%s 내부 API Client가 없습니다(hapbang.clients.%s.base-url 미설정).".formatted(app, app));
        }
        return found;
    }
}
