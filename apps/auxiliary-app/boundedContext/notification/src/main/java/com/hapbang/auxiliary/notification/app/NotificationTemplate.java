package com.hapbang.auxiliary.notification.app;

import java.util.Map;

/**
 * 알림 종류 하나의 문구 템플릿. 종류별 구현은 그 종류의 이벤트를 소비하는 작업(N5 이후)이 빈으로 더한다(ND-050).
 * 저장된 값만으로 같은 문구를 다시 만들 수 있어야 한다(ND-020). 파기된 회원의 닉네임을 바꾼 뒤에도 이 메서드로 다시 만든다(ND-051).
 */
public interface NotificationTemplate {

    /** 종류 코드 */
    String kind();

    /** 값으로 문구를 만든다. 다른 회원의 닉네임은 값의 {@code nicknames}에서 읽는다 */
    String render(Map<String, Object> payload);
}
