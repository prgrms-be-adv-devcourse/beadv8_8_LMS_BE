package com.hapbang.billing.app;

import java.util.List;
import java.util.Set;

import com.hapbang.billing.domain.EarningSourceType;
import com.hapbang.billing.domain.EarningStatus;

// 지급 예정 수익 출처 하나(강의 또는 출연)의 내부 API를 Billing 기준 값으로 읽는다 (#295).
// 호출 실패·계약에 맞지 않는 응답은 EarningSourceException으로 던진다(부분 결과를 돌려주지 않는다).
interface EarningSource {

    EarningSourceType type();

    // 회원의 아직 지급하지 않은 수익을 상태별로 합한 값
    EarningSummaryResult.Buckets summary(long userId);

    // 회원의 수익 목록 한 페이지(출처 순서: 상태 변경 최신순, ID 내림차순). statuses는 비어 있지 않다
    SourcePage earnings(long userId, Set<EarningStatus> statuses, int page, int size);

    record SourcePage(List<EarningItemResult> content, long totalElements) {

        public SourcePage {
            content = List.copyOf(content);
        }
    }
}
