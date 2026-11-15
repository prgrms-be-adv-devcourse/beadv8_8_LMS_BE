package com.hapbang.auxiliary.notification.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestExecutionListeners;

import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/**
 * 실시간 전달 시험(ND-027·ND-028·ND-055~ND-060). 실제 서버(랜덤 포트)와 PostgreSQL·Redis 컨테이너를 띄우고, 웹소켓에 STOMP 프레임을
 * 직접 써서 FE가 받는 프레임·종료 코드를 그대로 본다({@link RawStomp}).
 * <p>
 * Redis Pub/Sub 채널·티켓 키는 컨텍스트마다 다른 접두어로 나눈다(같은 Redis 컨테이너를 다른 컨텍스트와 함께 쓴다). 구독을 남기지 않도록
 * 클래스가 끝나면 컨텍스트를 닫는다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(classes = ModuleTestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=hapbang-module-test",
                "spring.modulith.events.jdbc.schema-initialization.enabled=true",
                "spring.modulith.events.completion-mode=delete",
                "spring.security.oauth2.client.registration.google.client-id=test",
                "spring.security.oauth2.client.registration.google.client-secret=test",
                "hapbang.storage.s3.endpoint=http://localhost:9",
                "hapbang.storage.s3.bucket=test",
                "hapbang.storage.s3.region=ap-northeast-2",
                "hapbang.storage.s3.key-prefix=test",
                "hapbang.internal-api.key=" + IntegrationTest.INTERNAL_API_KEY,
                "hapbang.notification.realtime.enabled=true",
                "hapbang.notification.realtime.redis-prefix=test-notification-${random.uuid}",
                "hapbang.notification.websocket.allowed-origins=http://localhost:5173"
        })
@Import({PostgresTestcontainersConfig.class, RedisTestcontainersConfig.class, TestNotificationTemplate.Config.class})
@DirtiesContext
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface NotificationRealtimeTest {
}
