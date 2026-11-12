package com.hapbang.auxiliary.search.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.hapbang.testsupport.RepositoryTest;

/** 증분 보정 커서 저장(#772). 모듈 SQL로 만든 search.sync_cursor에 PostgreSQL 컨테이너로 쓴다 */
@RepositoryTest
@Import(SyncCursorStore.class)
class SyncCursorStoreTest {

    private static final String NAME = SyncCursor.MEMBER_CHANGES;

    @Autowired
    private SyncCursorStore store;

    @Test
    @DisplayName("커서가 없으면 비어 있다")
    void empty() {
        assertThat(store.find(NAME)).isEmpty();
    }

    @Test
    @DisplayName("커서를 처음 옮기면 만들고, 다시 옮기면 바꾼다. 회원 앱이 준 마이크로초 시각을 그대로 남긴다")
    void moveTo() {
        LocalDateTime first = LocalDateTime.of(2026, 11, 10, 10, 15, 30, 123_456_000);
        store.moveTo(NAME, first, 7L);
        assertThat(store.find(NAME)).contains(new SyncCursor(NAME, first, 7L, null));

        LocalDateTime next = first.plusSeconds(5);
        store.moveTo(NAME, next, 3L);
        assertThat(store.find(NAME)).contains(new SyncCursor(NAME, next, 3L, null));
    }

    @Test
    @DisplayName("끝까지 읽은 시각을 남기고, 그 뒤 커서를 옮겨도 그 시각은 그대로다")
    void caughtUp() {
        LocalDateTime cursor = LocalDateTime.of(2026, 11, 10, 1, 0);
        LocalDateTime caughtUpAt = LocalDateTime.of(2026, 11, 10, 7, 0, 3);
        store.moveTo(NAME, cursor, 7L);

        store.caughtUp(NAME, caughtUpAt);
        store.moveTo(NAME, cursor.plusMinutes(1), 8L);

        assertThat(store.find(NAME)).contains(new SyncCursor(NAME, cursor.plusMinutes(1), 8L, caughtUpAt));
    }

    @Test
    @DisplayName("커서가 없을 때 끝까지 읽은 시각을 남기려 하면 아무것도 생기지 않는다")
    void caughtUpWithoutCursor() {
        store.caughtUp(NAME, LocalDateTime.of(2026, 11, 10, 7, 0));

        assertThat(store.find(NAME)).isEmpty();
    }

    @Test
    @DisplayName("다음 회차는 커서 시각에서 1분 뺀 시각부터 읽는다")
    void readFrom() {
        SyncCursor cursor = new SyncCursor(NAME, LocalDateTime.of(2026, 11, 10, 10, 15, 30), 7L, null);

        assertThat(cursor.readFrom(Duration.ofMinutes(1))).isEqualTo(LocalDateTime.of(2026, 11, 10, 10, 14, 30));
    }
}
