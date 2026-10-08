package com.hapbang.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;

/**
 * 하위 모듈의 JPA 관련 빈(엔티티, JpaRepository)만 PostgreSQL 컨테이너와 함께 띄우는 저장소 테스트.
 * <ul>
 *     <li>각 테스트는 트랜잭션 안에서 실행되고 끝나면 롤백된다({@link DataJpaTest} 기본 동작).</li>
 *     <li>트랜잭션 밖에서 커밋된 데이터는 {@link DatabaseCleanUpListener}가 테스트마다 비운다.</li>
 *     <li>JpaRepository가 아닌 @Repository 구현체(예: XxxRepositoryImpl)는 테스트 클래스에서 @Import 해야 한다.</li>
 *     <li>이벤트 리스너, 서비스 등은 로드되지 않는다. 필요하면 {@link IntegrationTest}를 쓴다.</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest
@ContextConfiguration(classes = ModuleTestConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface RepositoryTest {
}
