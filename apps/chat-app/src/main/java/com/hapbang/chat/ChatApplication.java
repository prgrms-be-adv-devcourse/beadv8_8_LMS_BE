package com.hapbang.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** chat-app의 독립 실행 진입점. 다른 애플리케이션의 빈은 등록하지 않는다. */
@SpringBootApplication(scanBasePackages = {"com.hapbang.chat", "com.hapbang.shared"})
@AutoConfigurationPackage(basePackages = {"com.hapbang.chat", "com.hapbang.shared"})
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
