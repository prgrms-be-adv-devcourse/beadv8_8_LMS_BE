package com.hapbang.auxiliary.chatbot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 자유 질문 설정 {@code hapbang.chatbot.chat} (#720). 대화 상태는 서버가 갖지 않고 FE가 요청마다 {@code history}로 보낸다.
 * 회원별 호출 제한은 FAQ 검색·대화의 제한({@code hapbang.chatbot.faq.rate-limit})을 함께 쓴다.
 *
 * @param historyMaxMessages     LLM에 넣는 최근 대화 수. 넘으면 앞(오래된 것)에서 버린다(오류 아님)
 * @param historyMaxTotalLength  넣는 대화 내용 글자 수 합의 상한. 넘으면 400
 * @param userMaxLength          대화 한 개(USER)의 최대 글자 수
 * @param assistantMaxLength     대화 한 개(ASSISTANT)의 최대 글자 수
 * @param timeout                한 번에 받기 전체 시간 상한(Tool 호출 왕복 포함). Gateway 30초 안. 근거 없는 출발값이라 직접 재서 고친다
 * @param streamTimeout          스트림 전체 시간 상한. Gateway 스트림 route(60s)·MVC 비동기 상한(50s)보다 짧게(FAQ 스트림과 같은 값)
 */
@Validated
@ConfigurationProperties("hapbang.chatbot.chat")
public record ChatbotChatProperties(
        @DefaultValue("12") @Positive int historyMaxMessages,
        @DefaultValue("8000") @Positive int historyMaxTotalLength,
        @DefaultValue("500") @Positive int userMaxLength,
        @DefaultValue("2000") @Positive int assistantMaxLength,
        @DefaultValue("25s") @NotNull Duration timeout,
        @DefaultValue("45s") @NotNull Duration streamTimeout) {
}
