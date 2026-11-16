package com.hapbang.auxiliary.chatbot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 로컬 확인 화면 (#720). local 프로필에서만 {@code /local/chatbot/}으로 챗봇 버튼·자유 질문을 직접 눌러 본다.
 * 화면은 jar의 {@code local-chatbot/}에 있지만 다른 프로필에는 이 경로가 없다(운영에서는 보이지 않는다). 보안 설정도 local에서만
 * {@code /local/**}을 연다. 화면이 부르는 API는 화면이 사용자 헤더를 직접 실어 보낸다(로컬에는 Gateway가 없다).
 */
@Profile("local")
@Configuration(proxyBeanMethods = false)
public class ChatbotLocalPageConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/local/chatbot/**").addResourceLocations("classpath:/local-chatbot/");
    }
}
