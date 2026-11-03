package com.hapbang.shared.billing.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

// 참조 유형별로 기간 안에 바뀐 보류 목록 한 페이지 (#577, 서비스 간 HP 대사의 "지갑에만 있는 보류" 찾기).
// 마지막 변경 시각·보류 순으로 준다. nextUpdatedAfter·nextAfterId는 항상 있고, 이 페이지의 마지막 보류 위치다(빈 페이지면 요청한 커서 그대로).
// hasMore가 true면 커서를 다음 요청에 그대로 넘겨 이어 읽고, false면 멈추고 커서를 저장해 다음 대사를 거기서 시작한다.
// nextAfterId는 커서 값으로만 쓴다(보류 ID의 뜻을 계약으로 삼지 않는다).
public record HoldStatePageDto(
        List<HoldStateDto> items,
        Instant nextUpdatedAfter,
        long nextAfterId,
        boolean hasMore
) {

    // 한 페이지 최대 건수
    public static final int MAX_SIZE = 500;

    public HoldStatePageDto {
        items = List.copyOf(items);
        Objects.requireNonNull(nextUpdatedAfter, "다음 커서 시각은 필수입니다.");
    }
}
