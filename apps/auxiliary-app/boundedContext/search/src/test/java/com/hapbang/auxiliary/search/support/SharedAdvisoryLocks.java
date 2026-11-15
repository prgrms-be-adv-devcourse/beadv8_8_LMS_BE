package com.hapbang.auxiliary.search.support;

import java.util.HashSet;
import java.util.Set;

import com.hapbang.auxiliary.global.scheduling.AdvisoryLockSession;

/**
 * pod 여러 대가 같은 DB에서 advisory lock을 잡는 모습을 메모리로 흉내 낸다(#773 "두 pod가 동시에 시작해도 한 번만"). {@link #session()}마다
 * pod 하나의 락 연결이고, 한 번호는 한 연결만 쥔다.
 */
public class SharedAdvisoryLocks {

    private final Set<Long> held = new HashSet<>();

    /** pod 하나의 락 연결 */
    public AdvisoryLockSession session() {
        Set<Long> mine = new HashSet<>();
        return new AdvisoryLockSession() {
            @Override
            public boolean tryLock(long key) {
                synchronized (held) {
                    if (held.contains(key)) {
                        return false;
                    }
                    held.add(key);
                    mine.add(key);
                    return true;
                }
            }

            @Override
            public boolean unlock(long key) {
                synchronized (held) {
                    if (!mine.remove(key)) {
                        return false;
                    }
                    held.remove(key);
                    return true;
                }
            }

            @Override
            public void close() {
                synchronized (held) {
                    held.removeAll(mine);
                    mine.clear();
                }
            }
        };
    }
}
