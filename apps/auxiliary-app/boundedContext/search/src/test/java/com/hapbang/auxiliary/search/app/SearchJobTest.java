package com.hapbang.auxiliary.search.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 검색 작업 락 번호(#771) */
class SearchJobTest {

    @Test
    @DisplayName("락 번호는 #771 × 1000 + 순번이고 서로 겹치지 않는다. 태그는 search-로 시작한다")
    void lockKeys() {
        assertThat(Arrays.stream(SearchJob.values()).map(SearchJob::lockKey)).doesNotHaveDuplicates()
                .allMatch(key -> key / 1000 == 771);
        assertThat(Arrays.stream(SearchJob.values()).map(SearchJob::tag)).allMatch(tag -> tag.startsWith("search-"));
    }
}
