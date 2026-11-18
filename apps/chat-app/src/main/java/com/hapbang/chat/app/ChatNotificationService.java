package com.hapbang.chat.app;

import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;

/**
 * 출연 이벤트를 두 회원의 1:1 방에 채팅 알림 메시지로 남긴다. 메서드마다 하나의 쓰기 트랜잭션이고, 같은 출연 건의 같은 알림은
 * 한 번만 남긴다. 동시에 같은 방·알림을 만들면 유일 제약에 걸리므로 호출한 Facade가 새 트랜잭션에서 다시 시도한다.
 */
public interface ChatNotificationService {

    /** 출연 요청 도착: 호스트가 게스트에게 */
    void notifyAppearanceRequestSent(AppearanceRequestDto request);

    /** 출연 모집 지원 도착: 게스트가 호스트에게 */
    void notifyAppearanceApplicationSubmitted(AppearanceApplicationDto application);
}
