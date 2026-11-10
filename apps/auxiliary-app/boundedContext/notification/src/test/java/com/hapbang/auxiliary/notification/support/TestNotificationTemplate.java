package com.hapbang.auxiliary.notification.support;

import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.hapbang.auxiliary.notification.app.NotificationTemplate;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;

/**
 * 테스트 전용 알림 종류와 문구. 종류별 문구는 그 종류를 소비하는 작업에서 정하므로(ND-050) 운영 코드에는 템플릿이 없다.
 * 값의 {@code actorId}가 가리키는 회원 닉네임과 {@code title}로 문구를 만든다.
 */
public final class TestNotificationTemplate implements NotificationTemplate {

    public static final String KIND = "TEST_MENTION";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String render(Map<String, Object> payload) {
        String actorId = String.valueOf(payload.get("actorId"));
        return NotificationPayload.nicknames(payload).get(actorId) + "님이 " + payload.get("title") + "에 반응했습니다.";
    }

    /** 테스트 컨텍스트에 이 템플릿을 등록한다 */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        TestNotificationTemplate testNotificationTemplate() {
            return new TestNotificationTemplate();
        }
    }
}
