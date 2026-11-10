package com.hapbang.auxiliary.notification.app;

import com.hapbang.auxiliary.notification.app.dto.NotificationFrame;

/**
 * 실시간 프레임을 받는 사람의 연결이 있는 서버로 넘긴다(Redis Pub/Sub, ND-027). 실시간 전달을 끈 실행에는 빈이 없다.
 * 실패는 예외로 알린다. 부르는 쪽이 저장 결과와 관계없이 버린다.
 */
public interface NotificationRealtimePublisher {

    void publish(Long receiverUserId, NotificationFrame frame);
}
