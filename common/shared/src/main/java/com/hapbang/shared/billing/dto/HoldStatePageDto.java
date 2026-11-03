package com.hapbang.shared.billing.dto;

import java.time.Instant;
import java.util.List;

// 참조 유형별로 기간 안에 바뀐 보류 목록 한 페이지 (#577, 서비스 간 HP 대사의 "지갑에만 있는 보류" 찾기).
// 마지막 변경 시각·보류 순으로 준다. 다음 페이지가 있으면 nextUpdatedAfter·nextAfterId를 다음 요청에 그대로 넘기고, 없으면 둘 다 null이다.
// nextAfterId는 커서 값으로만 쓴다(보류 ID의 뜻을 계약으로 삼지 않는다).
public record HoldStatePageDto(
        List<HoldStateDto> items,
        Instant nextUpdatedAfter,
        Long nextAfterId
) {

    // 한 페이지 최대 건수
    public static final int MAX_SIZE = 500;

    public HoldStatePageDto {
        items = List.copyOf(items);
    }
}
