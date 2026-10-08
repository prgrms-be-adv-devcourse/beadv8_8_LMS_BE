package com.hapbang.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;

/**
 * 하위 모듈의 전체 컨텍스트를 PostgreSQL 컨테이너와 함께 띄우는 통합 테스트.
 * 각 테스트가 끝나면 모든 테이블을 비운다({@link DatabaseCleanUpListener}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
// 운영은 archive지만, 테스트는 처리 완료된 이벤트를 바로 삭제해서 테스트 간에 기록이 쌓이지 않게 한다.
@SpringBootTest(classes = ModuleTestConfiguration.class, properties = {
        "spring.modulith.events.jdbc.schema-initialization.enabled=true",
        "spring.modulith.events.completion-mode=delete"
})
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface IntegrationTest {
}
