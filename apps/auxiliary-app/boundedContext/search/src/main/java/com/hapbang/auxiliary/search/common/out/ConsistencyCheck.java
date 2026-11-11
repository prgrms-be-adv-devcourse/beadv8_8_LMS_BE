package com.hapbang.auxiliary.search.common.out;

import java.time.LocalDateTime;

/**
 * 문서 수 정합성 점검 결과 한 행(#772, {@code search.consistency_check}).
 *
 * @param target      비교한 대상({@link #INFLUENCER})
 * @param sourceCount 원본(회원 앱 통계 API)의 정상 인플루언서 수
 * @param indexCount  ES의 보이는(숨기지 않은) 문서 수
 * @param checkedAt   점검한 시각(KST)
 */
public record ConsistencyCheck(String target, long sourceCount, long indexCount, LocalDateTime checkedAt) {

    /** 인플루언서 검색 대상 */
    public static final String INFLUENCER = "influencer";

    /** 원본 − index. 양수면 index에 빠진 문서(승급을 놓침), 음수면 보이면 안 되는 문서(강등을 놓침)가 있다 */
    public long difference() {
        return sourceCount - indexCount;
    }
}
