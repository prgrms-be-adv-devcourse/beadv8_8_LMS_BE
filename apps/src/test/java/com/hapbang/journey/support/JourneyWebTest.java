package com.hapbang.journey.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestExecutionListeners;

import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

/**
 * 웹소켓(STOMP)까지 보는 여정 테스트 (#326 시나리오 5). {@link JourneyTest}와 같지만 실제 포트로 서버를 띄운다.
 * REST는 MockMvc로, STOMP는 그 포트의 {@code /api/v1/chats/ws}로 연결한다. 둘은 같은 애플리케이션 컨텍스트라
 * 로그아웃·회원 변경 이벤트가 열린 웹소켓 연결을 닫는 것까지 이어서 볼 수 있다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, JourneyFixtures.class})
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@DirtiesContext
public @interface JourneyWebTest {
}
