package com.hapbang.auxiliary.search.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import co.elastic.clients.util.ObjectBuilder;

/** 통계 bulk에서 일부 항목이 실패해도 나머지는 반영하고 예외를 던지지 않는다(#771 코드 리뷰). 실패를 실제 ES로 만들기 어려워 응답을 꾸민다 */
class InfluencerDocumentStoreBulkFailureTest {

    @Test
    @DisplayName("문서 없음만 MISSING이고, alias·index가 없는 404를 포함한 그 밖의 실패는 FAILED다. 나머지 결과는 그대로다")
    void partialFailure() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        // mock은 final 메서드(빌더를 받는 bulk)까지 가로채므로 store가 부르는 그 메서드를 꾸민다
        when(client.bulk(ArgumentMatchers.<Function<BulkRequest.Builder, ObjectBuilder<BulkRequest>>>any())).thenReturn(BulkResponse.of(r -> r.errors(true).took(1)
                .items(i -> i.operationType(OperationType.Update).index("search-influencers-x").id("1").status(200)
                        .result("updated"))
                .items(i -> i.operationType(OperationType.Update).index("search-influencers-x").id("2").status(404)
                        .error(e -> e.type("document_missing_exception").reason("없음")))
                .items(i -> i.operationType(OperationType.Update).index("search-influencers-x").id("3").status(409)
                        .error(e -> e.type("version_conflict_engine_exception").reason("겹침")))
                .items(i -> i.operationType(OperationType.Update).index("search-influencers-x").id("4").status(200)
                        .result("noop"))
                .items(i -> i.operationType(OperationType.Update).index("search-influencers").id("5").status(404)
                        .error(e -> e.type("index_not_found_exception").reason("alias 없음")))));

        Map<Long, WriteOutcome> outcomes = new InfluencerDocumentStore(client).applyStats(List.of(
                stats(1), stats(2), stats(3), stats(4), stats(5)));

        assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of(1L, WriteOutcome.UPDATED,
                2L, WriteOutcome.MISSING, 3L, WriteOutcome.FAILED, 4L, WriteOutcome.NOOP, 5L, WriteOutcome.FAILED));
    }

    private static InfluencerStats stats(long userId) {
        return new InfluencerStats(userId, List.of(), null, 0, null, 0);
    }
}
