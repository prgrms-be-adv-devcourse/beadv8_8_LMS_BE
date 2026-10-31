package com.hapbang.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * chat-app의 독립 실행 진입점. 다른 애플리케이션의 빈은 등록하지 않는다.
 * <p>
 * shared의 공통 HTTP Client({@code com.hapbang.shared.*.out})는 스캔하지 않는다. chat-app의 HTTP 호출은 자기 {@code @HttpExchange}
 * 인터페이스로 만든다(회원 내부 API는 {@code out.member}). shared의 엔티티 부모 타입은 JPA 패키지({@link AutoConfigurationPackage})로 쓴다.
 */
@SpringBootApplication(scanBasePackages = "com.hapbang.chat")
@AutoConfigurationPackage(basePackages = {"com.hapbang.chat", "com.hapbang.shared"})
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
