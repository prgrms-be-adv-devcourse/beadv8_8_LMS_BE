package com.hapbang.testsupport;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import jakarta.persistence.EntityManagerFactory;

/**
 * 각 테스트 메서드가 끝난 뒤 DB를 비운다. {@link IntegrationTest}에 등록되어 있다.
 * <p>
 * 순서를 TransactionalTestExecutionListener(4000)보다 앞에 두면 afterTestMethod는 더 늦게 실행된다.
 * 그래서 @Transactional 테스트의 롤백이 끝난 뒤에 정리하므로, 열린 트랜잭션과 TRUNCATE가 서로 막지 않는다.
 */
public class DatabaseCleanUpListener extends AbstractTestExecutionListener {

    @Override
    public int getOrder() {
        return 3_000;
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        EntityManagerFactory entityManagerFactory = testContext.getApplicationContext().getBean(EntityManagerFactory.class);
        new DatabaseCleanUp(entityManagerFactory).execute();
    }
}
