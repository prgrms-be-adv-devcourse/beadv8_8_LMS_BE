package com.hapbang.auxiliary.search.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.hapbang.testsupport.RepositoryTest;

/** 문서 수 정합성 점검 결과 저장(#772, PR #833 리뷰 Q2). 모듈 SQL로 만든 search.consistency_check에 PostgreSQL 컨테이너로 쓴다 */
@RepositoryTest
@Import(ConsistencyCheckStore.class)
class ConsistencyCheckStoreTest {

    private static final String TARGET = ConsistencyCheck.INFLUENCER;
    private static final LocalDateTime CHECKED_AT = LocalDateTime.of(2026, 11, 10, 10, 20, 0, 123_456_000);

    @Autowired
    private ConsistencyCheckStore store;

    @Test
    @DisplayName("점검 결과가 없으면 비어 있다")
    void empty() {
        assertThat(store.find(TARGET)).isEmpty();
    }

    @Test
    @DisplayName("처음 저장하면 만들고, 다시 저장하면 바꾼다. 차이는 원본 − index다")
    void save() {
        store.save(new ConsistencyCheck(TARGET, 120, 118, CHECKED_AT));
        assertThat(store.find(TARGET)).contains(new ConsistencyCheck(TARGET, 120, 118, CHECKED_AT));
        assertThat(store.find(TARGET).orElseThrow().difference()).isEqualTo(2);

        store.save(new ConsistencyCheck(TARGET, 120, 121, CHECKED_AT.plusMinutes(10)));
        assertThat(store.find(TARGET)).contains(new ConsistencyCheck(TARGET, 120, 121, CHECKED_AT.plusMinutes(10)));
        assertThat(store.find(TARGET).orElseThrow().difference()).isEqualTo(-1);
    }

    @Test
    @DisplayName("점검하지 못한 회차에는 결과를 지운다")
    void clear() {
        store.save(new ConsistencyCheck(TARGET, 120, 118, CHECKED_AT));

        store.clear(TARGET);

        assertThat(store.find(TARGET)).isEmpty();
    }
}
