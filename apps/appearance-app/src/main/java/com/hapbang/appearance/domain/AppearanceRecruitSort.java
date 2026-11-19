package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 출연 모집 공개 목록의 정렬. 기본은 최신순이고, 기준 값이 같으면 최신순으로 잇는다.
 * 요청의 {@code sort} 파라미터로 받으며, 필드 이름 정렬(Spring {@code Pageable}의 {@code sort=createdAt,desc})은 받지 않는다.
 * Swagger 설명은 쿼리 파라미터에 둔다({@code ApiV1AppearanceRecruitSpec#getOpen}).
 */
@Getter
@RequiredArgsConstructor
public enum AppearanceRecruitSort {
    LATEST("등록 최신순"),
    SCHEDULED_SOON("방송 예정 일시가 빠른 순"),
    DEADLINE_SOON("모집 마감 일시가 빠른 순"),
    FEE_HIGH("1인당 출연료가 높은 순");

    private final String description;
}
