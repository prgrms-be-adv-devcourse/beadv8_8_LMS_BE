package com.hapbang.chat.in.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestExecutionListeners;

import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/**
 * 채팅 E2E 테스트. 실제 서버(랜덤 포트)를 띄우고 RestTestClient·STOMP 클라이언트로 HTTP·웹소켓 요청을 보낸다.
 * 로그인은 {@link E2ELoginConfig}의 테스트 전용 로그인 경로로 실제 세션을 만든다.
 * <p>
 * 실시간 전달은 운영과 같은 경로다: 저장 트랜잭션의 outbox → relay → Redis Streams → consumer → Redis Pub/Sub → 로컬 STOMP.
 * Redis 컨테이너를 쓰고 이 테스트용 namespace로 Stream·채널을 나눈다.
 * <p>
 * 전달 파이프라인은 공유 PostgreSQL의 outbox를 계속 읽으므로, 캐시된 다른 컨텍스트의 relay가 이 테스트의 작업을 가져가지 않도록
 * 클래스가 끝나면 컨텍스트를 닫는다({@link DirtiesContext}). 배치 실행을 켠 테스트라 이 규칙은 testing.md "배치"와도 같다.
 * 같은 클래스의 메서드 사이에서는 {@link ChatPipelineIsolationListener}가 파이프라인을 멈추고 이 namespace의 Stream만 지운 뒤
 * 다시 시작한다(앞 메서드가 남긴 작업이 다음 메서드로 새지 않게).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(classes = ModuleTestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=hapbang-module-test",
                "spring.modulith.events.jdbc.schema-initialization.enabled=true",
                "spring.modulith.events.completion-mode=delete",
                // global의 SecurityConfig(oauth2Login)가 구글 등록 정보를 요구한다
                "spring.security.oauth2.client.registration.google.client-id=test",
                "spring.security.oauth2.client.registration.google.client-secret=test",
                // global 내부 API 보안(InternalApiSecurityConfig)이 키를 요구한다 (#300)
                "hapbang.internal-api.key=" + IntegrationTest.INTERNAL_API_KEY,
                // 웹소켓 허용 출처 검사(#235)용 프론트 출처
                "hapbang.chat.websocket.allowed-origins=http://localhost:5173",
                // 운영과 같은 실시간 전달 경로
                "hapbang.chat.realtime.enabled=true",
                "hapbang.chat.delivery.pipeline.enabled=true",
                // 전달 파이프라인의 relay·Stream worker는 배치 실행이 켜져 있어야 돈다(@ConditionalOnSchedulingEnabled)
                "hapbang.scheduling.enabled=true",
                // 컨텍스트마다 다른 namespace로 Stream·채널을 나눈다(클래스마다 컨텍스트를 닫으므로 클래스마다 다르다)
                "hapbang.chat.redis.namespace=test-chat-e2e-${random.uuid}",
                // 메서드 사이에 파이프라인을 멈출 때 worker가 블로킹 읽기에서 빨리 돌아오게 한다
                "hapbang.chat.delivery.consumer.block=200ms"
        })
@Import({PostgresTestcontainersConfig.class, RedisTestcontainersConfig.class, E2ELoginConfig.class,
        E2ELoginConfig.TestSessionLoginController.class})
@DirtiesContext
@TestExecutionListeners(listeners = {ChatE2EDatabaseCleanUpListener.class, ChatPipelineIsolationListener.class},
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface ChatE2ETest {
}
