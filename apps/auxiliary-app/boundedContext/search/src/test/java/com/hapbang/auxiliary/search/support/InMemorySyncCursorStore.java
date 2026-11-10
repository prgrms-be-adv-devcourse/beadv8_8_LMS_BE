package com.hapbang.auxiliary.search.support;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.hapbang.auxiliary.search.out.SyncCursor;
import com.hapbang.auxiliary.search.out.SyncCursorStore;

/** DB 없이 커서를 메모리에 두는 가짜(#772). SQL은 {@code SyncCursorStoreTest}가 본다 */
public class InMemorySyncCursorStore extends SyncCursorStore {

    private final Map<String, SyncCursor> cursors = new HashMap<>();

    public InMemorySyncCursorStore() {
        super(null);
    }

    @Override
    public Optional<SyncCursor> find(String name) {
        return Optional.ofNullable(cursors.get(name));
    }

    @Override
    public void moveTo(String name, LocalDateTime memberUpdatedAt, long memberId) {
        LocalDateTime caughtUpAt = find(name).map(SyncCursor::caughtUpAt).orElse(null);
        cursors.put(name, new SyncCursor(name, memberUpdatedAt, memberId, caughtUpAt));
    }

    @Override
    public void caughtUp(String name, LocalDateTime at) {
        find(name).ifPresent(cursor -> cursors.put(name,
                new SyncCursor(name, cursor.memberUpdatedAt(), cursor.memberId(), at)));
    }
}
