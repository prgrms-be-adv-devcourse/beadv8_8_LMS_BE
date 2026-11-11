package com.hapbang.lecture.app;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션이 커밋된 뒤에만 할 일(돈 흐름·결정 로그). 롤백된 변경이 "~했다" 로그로 남지 않게 한다 (#681, docs/dev/logging.md).
 * 트랜잭션 동기화가 없으면(트랜잭션 밖 호출) 바로 실행한다.
 */
final class AfterCommit {

    private AfterCommit() {
    }

    static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
