package com.hapbang.auxiliary.notification.app;

import java.time.Instant;

/** 알림 시작 시각 기록(ND-095). 처음 한 번만 쓰고 그 뒤에는 처음 값을 돌려준다 */
public interface AlertStartStore {

    /**
     * @param key 설정 키
     * @param now 아직 기록이 없으면 쓸 시각
     * @return 기록된 시각(이번에 썼거나 전에 쓴 값)
     */
    Instant recordIfAbsent(String key, Instant now);
}
