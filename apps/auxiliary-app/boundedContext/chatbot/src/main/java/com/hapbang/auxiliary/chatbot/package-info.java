/**
 * 챗봇 모듈: FAQ, 강의·인플루언서 추천, 추천에 쓰는 데이터 동기화, 대화.
 * <p>
 * 공통 모듈({@code global})만 의존한다. 같은 앱의 다른 업무 모듈(검색·알림)을 직접 참조하지 않고, 필요하면 이벤트로 주고받는다.
 * 테이블은 {@code chatbot} schema에만 둔다.
 */
@ApplicationModule(displayName = "Chatbot", allowedDependencies = "global")
package com.hapbang.auxiliary.chatbot;

import org.springframework.modulith.ApplicationModule;
