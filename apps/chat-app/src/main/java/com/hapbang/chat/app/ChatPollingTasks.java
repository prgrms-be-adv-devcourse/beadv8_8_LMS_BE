package com.hapbang.chat.app;

import java.util.Set;

/**
 * 1분보다 짧은 주기로 도는 polling 작업 (#631). 업무 진입점이 아니라 trace를 만들지 않는다(global.config.ObservationConfig).
 * 작업 클래스가 이 패키지 안에만 보이므로 여기서 판정한다.
 */
public final class ChatPollingTasks {

    private static final Set<Class<?>> POLLING = Set.of(ChatRoomActivityBatch.class, ChatPresenceHeartbeat.class);

    private ChatPollingTasks() {
    }

    public static boolean isPolling(Class<?> taskClass) {
        return POLLING.contains(taskClass);
    }
}
