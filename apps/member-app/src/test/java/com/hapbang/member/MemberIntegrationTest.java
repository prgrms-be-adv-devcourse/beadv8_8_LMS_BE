package com.hapbang.member;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;

import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

/**
 * 회원 앱 통합 테스트. {@link IntegrationTest}에 공통 Kafka 컨테이너({@link KafkaTestcontainersConfig})를 더한다.
 * <p>
 * 회원 가입·수정 이벤트는 운영과 같이 Kafka({@code member.events.v1})로 외부화되므로, 모든 통합 테스트가 이 컨테이너로 보낸다.
 * 컨테이너는 테스트 JVM에서 하나를 함께 쓰고, 같은 설정의 테스트는 Spring 컨텍스트도 함께 쓴다.
 * 같은 토픽에 다른 테스트의 메시지가 섞이므로 Kafka 메시지를 확인하는 테스트는 자신이 만든 회원 ID(key)로 거른다.
 * <p>
 * 테스트마다 보내는 중인 발행 기록이 끝날 때까지 기다린다({@link PublicationCompletionListener}).
 * 가장 가까운 {@code @TestExecutionListeners}만 쓰이므로 {@link IntegrationTest}의 DB 정리 리스너도 함께 적는다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@IntegrationTest
@Import(KafkaTestcontainersConfig.class)
@TestExecutionListeners(listeners = {PublicationCompletionListener.class, DatabaseCleanUpListener.class},
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface MemberIntegrationTest {
}
