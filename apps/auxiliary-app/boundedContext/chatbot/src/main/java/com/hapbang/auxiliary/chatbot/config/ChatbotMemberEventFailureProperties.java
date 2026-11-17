package com.hapbang.auxiliary.chatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 회원 이벤트 실패 기록 관리 (#976).
 *
 * @param retryAllLimit 관리자가 고르지 않고 다시 처리할 때 한 번에 처리하는 최대 건수(오래된 순). 앱이 같이 쓰는 DB 연결을 오래 잡지 않게 한다.
 *                      골라서 보낼 수 있는 ID 수(100)를 넘지 않는다
 */
@Validated
@ConfigurationProperties("hapbang.chatbot.member-event-failure")
public record ChatbotMemberEventFailureProperties(
        @DefaultValue("100") @Min(1) @Max(100) int retryAllLimit
) {
}
