package com.hapbang.member;

import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * 각 테스트 메서드가 끝나면 Kafka로 보내는 중인 발행 기록이 모두 끝날 때까지 기다린다. {@link MemberIntegrationTest}에 등록되어 있다.
 * <p>
 * 회원 이벤트는 커밋 뒤 비동기로 Kafka에 보내므로, 기다리지 않으면 미완료 발행 기록이 공유 DB에 남아 다음 테스트로 넘어간다.
 * 그러면 재기동 재발행을 켠 다른 컨텍스트가 그 기록을 다시 보내거나, 테스트 JVM 종료 때 미완료로 남는다.
 * 롤백(TransactionalTestExecutionListener, 4000) 뒤, DB 정리(DatabaseCleanUpListener, 3000) 전에 실행한다.
 */
public class PublicationCompletionListener extends AbstractTestExecutionListener {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Override
    public int getOrder() {
        return 3_500;
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        EventPublicationRepository publications = testContext.getApplicationContext()
                .getBean(EventPublicationRepository.class);
        await("미완료 발행 기록이 모두 끝나기")
                .atMost(TIMEOUT)
                .until(() -> publications.findIncompletePublications().isEmpty());
    }
}
