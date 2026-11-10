package com.hapbang.auxiliary.search.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 검색 작업 락 번호(#771, #772, #773) */
class SearchJobTest {

    @Test
    @DisplayName("락 번호는 그 작업을 더한 검색 이슈 번호(#771, #772, #773) × 1000 + 순번이고 서로 겹치지 않는다. 태그는 search-로 시작한다")
    void lockKeys() {
        assertThat(Arrays.stream(SearchJob.values()).map(SearchJob::lockKey)).doesNotHaveDuplicates()
                .allMatch(key -> Set.of(771L, 772L, 773L).contains(key / 1000));
        assertThat(Arrays.stream(SearchJob.values()).map(SearchJob::tag)).allMatch(tag -> tag.startsWith("search-"));
    }
}
