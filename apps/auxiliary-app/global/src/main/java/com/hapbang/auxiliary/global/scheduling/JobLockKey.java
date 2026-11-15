package com.hapbang.auxiliary.global.scheduling;

/**
 * {@link JobLock}으로 한 번에 한 곳에서만 실행할 작업 (#716). 모듈마다 자기 enum으로 구현한다(예: 챗봇 {@code ChatbotJob}).
 * <p>
 * advisory lock 번호는 database마다 따로인데, auxiliary-app은 database 하나를 모든 모듈(챗봇·검색·알림)이 함께 쓴다.
 * 그래서 번호가 모듈 사이에서도 겹치면 안 된다. 그 작업을 더한 <b>이슈 번호 × 1000 + 순번</b>을 쓰고, 한 번 정한 번호는 바꾸지 않는다.
 * 모듈 enum 안에서 겹치지 않는지는 각 모듈 테스트가, 이슈 번호가 다르면 모듈 사이에서도 겹치지 않는다.
 */
public interface JobLockKey {

    /** {@code pg_try_advisory_lock}에 넘기는 번호 */
    long lockKey();

    /** 로그·지표 태그에 쓰는 이름. 모듈 이름으로 시작한다(예: {@code chatbot-appearance-sync}) */
    String tag();
}
