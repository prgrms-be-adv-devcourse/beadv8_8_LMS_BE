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
 * apps 사용자 여정 통합 테스트 (#326). 모듈을 대역 없이 실제 빈·Modulith 이벤트·Billing·JDBC 세션으로 띄우고 HTTP(MockMvc)로 부른다.
 * <ul>
 *     <li>다른 apps 통합 테스트와 같은 구성(PostgreSQL 컨테이너, 테스트마다 JPA 테이블 정리)</li>
 *     <li>{@code csrf()}가 컨텍스트의 CSRF 저장소를 바꿔 다른 테스트(SecurityCookieTest)를 깨므로 컨텍스트를 재사용하지 않는다</li>
 *     <li>외부 API(구글·치지직·토스)만 대역. 스케줄러는 꺼져 있어 배치는 UseCase를 직접 부른다</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@DirtiesContext
public @interface JourneyTest {
}
