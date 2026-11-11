package com.hapbang.chat.app;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 단체방 시험용 설정. 출연 내부 API 대신 메모리 구현({@link InMemoryChatRecruitSource})을 쓴다. 운영 HTTP 어댑터는 그대로 등록되지만
 * 주입 대상은 이 빈이다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ChatGroupTestConfig {

    @Bean
    @Primary
    InMemoryChatRecruitSource inMemoryChatRecruitSource() {
        return new InMemoryChatRecruitSource();
    }
}
