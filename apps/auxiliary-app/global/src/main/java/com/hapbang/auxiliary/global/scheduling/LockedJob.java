package com.hapbang.auxiliary.global.scheduling;

/**
 * 한 번에 한 pod에서만 실행해야 하는 작업과 그 advisory lock 번호. 각 모듈이 enum으로 구현한다(예: {@code ChatbotJob}).
 * <p>
 * advisory lock은 database마다 따로인데 auxiliary-app은 모든 모듈이 database 하나({@code auxiliary})를 쓴다. 그래서 번호는
 * 앱 전체에서 겹치면 안 된다. 번호는 그 작업을 더한 <b>이슈 번호 × 1000 + 순번</b>으로 정한다. 이슈 번호는 저장소 안에서 하나뿐이라
 * 모듈끼리 따로 정해도 겹치지 않는다. 한 번 정한 번호는 바꾸지 않는다.
 */
public interface LockedJob {

    /** {@code pg_try_advisory_lock}에 넘기는 번호 */
    long lockKey();

    /** 로그·지표 태그에 쓰는 이름. 모듈 이름으로 시작한다(예: {@code chatbot-faq-sync}) */
    String tag();
}
