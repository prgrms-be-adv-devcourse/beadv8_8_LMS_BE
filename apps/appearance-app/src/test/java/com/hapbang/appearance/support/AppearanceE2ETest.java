package com.hapbang.appearance.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.ModuleTestConfiguration;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

/**
 * 출연 E2E 테스트 (#390). 실제 서버(랜덤 포트)를 띄우고 RestAssured로 HTTP 요청을 보낸다.
 * 보안 필터(세션 로그인, CSRF)·직렬화·오류 응답이 운영과 같은 경로를 거친다.
 * <ul>
 *     <li>나머지는 {@link AppearanceIntegrationTest}와 같다: PostgreSQL 컨테이너, 테스트마다 DB 비우기, Billing 대역, {@link AppearanceTestClock}.</li>
 *     <li>{@code @SpringBootTest}는 하나만 둘 수 있어 팀 공통 {@link IntegrationTest}를 쓰지 못한다. 그래서 그 설정 값을 같게 옮겨 둔다
 *     (공통 설정이 바뀌면 여기도 맞춘다).</li>
 *     <li>로그인은 {@link E2ELogin} 한 곳에서 만든다. RestAssured 포트는 테스트마다 이 서버의 포트로 맞춘다 ({@link RestAssuredPortExtension}).</li>
 * </ul>
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
                "hapbang.internal-api.key=" + IntegrationTest.INTERNAL_API_KEY
        })
@Import({PostgresTestcontainersConfig.class, AppearanceTestClockConfig.class,
        E2ELoginConfig.class, E2ELoginConfig.TestSessionLoginController.class})
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@MockitoBean(types = BillingClient.class, reset = MockReset.AFTER)
@ExtendWith({AppearanceTestClock.ResetExtension.class, RestAssuredPortExtension.class})
public @interface AppearanceE2ETest {
}
