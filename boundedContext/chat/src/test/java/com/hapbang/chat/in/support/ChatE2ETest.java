package com.hapbang.chat.in.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;

import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

/**
 * 채팅 E2E 테스트. 실제 서버(랜덤 포트)를 띄우고 RestTestClient·STOMP 클라이언트로 HTTP·웹소켓 요청을 보낸다.
 * 로그인은 {@link E2ELoginConfig}의 테스트 전용 로그인 경로로 실제 세션을 만든다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(classes = ModuleTestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.modulith.events.jdbc.schema-initialization.enabled=true",
                "spring.modulith.events.completion-mode=delete",
                // global의 SecurityConfig(oauth2Login)가 구글 등록 정보를 요구한다
                "spring.security.oauth2.client.registration.google.client-id=test",
                "spring.security.oauth2.client.registration.google.client-secret=test"
        })
@Import({PostgresTestcontainersConfig.class, E2ELoginConfig.class, E2ELoginConfig.TestSessionLoginController.class})
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface ChatE2ETest {
}
