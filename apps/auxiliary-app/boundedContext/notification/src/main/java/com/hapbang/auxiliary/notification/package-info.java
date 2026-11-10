/**
 * 알림 모듈: 알림센터 알림 생성·보관·실시간 전달(docs/notification). 지금은 뼈대(N1)로 회원 복제본과 Kafka 소비·DLT 공통 설정만 있다.
 * <p>
 * 공통 모듈({@code global})만 의존한다. 같은 앱의 다른 업무 모듈(챗봇·검색)을 직접 참조하지 않고, 필요하면 이벤트로 주고받는다.
 * 테이블은 {@code notification} schema에만 둔다(ND-007).
 */
@ApplicationModule(displayName = "Notification", allowedDependencies = "global")
package com.hapbang.auxiliary.notification;

import org.springframework.modulith.ApplicationModule;
