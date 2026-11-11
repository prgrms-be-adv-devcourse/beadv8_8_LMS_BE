package com.hapbang.auxiliary.search.common.out;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;

/**
 * ES 거절 중 "그 회원의 값 때문"만 건너뛸 대상으로 가른다(PR #833 리뷰 Q1). 오류 종류는 ES 9.5.5로 시험한 값이다:
 * 값이 칸 규칙에 안 맞으면 document_parsing_exception, script 오류는 illegal_argument_exception(← script_exception),
 * mapping에 없는 칸은 strict_dynamic_mapping_exception
 */
class DocumentWritesRejectionTest {

    @ParameterizedTest(name = "{0} {1} → 건너뜀 {2}")
    @CsvSource({
            "400, document_parsing_exception, true",
            "400, mapper_parsing_exception, true",
            "400, illegal_argument_exception, false",
            "400, strict_dynamic_mapping_exception, false",
            "400, index_closed_exception, false",
            "429, es_rejected_execution_exception, false",
            "503, unavailable_shards_exception, false"
    })
    void rejectedByContentOnlyForMemberValue(int status, String type, boolean rejected) {
        ElasticsearchException e = new ElasticsearchException("update",
                ErrorResponse.of(r -> r.status(status).error(c -> c.type(type).reason("시험"))));

        assertThat(DocumentWrites.isRejectedByContent(e)).isEqualTo(rejected);
    }
}
