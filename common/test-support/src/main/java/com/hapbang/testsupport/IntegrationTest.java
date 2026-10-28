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
        // 독립 앱의 운영 연결·배치·프로필 설정이 모듈 테스트에 유입되지 않게 한다.
        "spring.config.name=hapbang-module-test",
        "spring.modulith.events.jdbc.schema-initialization.enabled=true",
        "spring.modulith.events.completion-mode=delete",
        // global의 SecurityConfig(oauth2Login)가 구글 등록 정보를 요구한다
        "spring.security.oauth2.client.registration.google.client-id=test",
        "spring.security.oauth2.client.registration.google.client-secret=test",
        // global의 S3 빈(S3FileStorage)을 주입받을 수 있게 가짜 설정을 둔다. 호출하지 않으면 연결하지 않는다.
        // 실제로 S3를 호출하는 테스트는 S3MockTestcontainersConfig를 @Import 한다
        "hapbang.storage.s3.endpoint=http://localhost:9",
        "hapbang.storage.s3.bucket=test",
        "hapbang.storage.s3.region=ap-northeast-2",
        "hapbang.storage.s3.key-prefix=test",
        // global의 내부 API 보안(InternalApiSecurityConfig)이 키를 요구한다. 내부 API 테스트는 이 키로 호출한다
        "hapbang.internal-api.key=" + IntegrationTest.INTERNAL_API_KEY
})
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface IntegrationTest {

    /** 테스트 내부 API 키 */
    String INTERNAL_API_KEY = "test-internal-api-key";
}
