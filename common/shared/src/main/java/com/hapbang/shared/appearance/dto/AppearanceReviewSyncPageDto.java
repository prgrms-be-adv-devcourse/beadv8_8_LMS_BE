package com.hapbang.shared.appearance.dto;

import java.util.List;
import java.util.Objects;

/**
 * 출연 리뷰 동기화 내부 API 한 페이지 (#713). 공개된 리뷰만, 리뷰 ID 오름차순 키셋 페이지다.
 *
 * @param items       이 페이지의 공개된 리뷰
 * @param nextAfterId 다음 페이지를 부를 때 넘길 afterId(이 페이지 마지막 리뷰 ID). 마지막 페이지면 null
 */
public record AppearanceReviewSyncPageDto(List<AppearanceReviewSyncDto> items, Long nextAfterId) {

    /** 한 번에 받을 수 있는 최대 건수 */
    public static final int MAX_SIZE = AppearanceSyncPageDto.MAX_SIZE;

    // 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다. null 항목도 받지 않는다
    public AppearanceReviewSyncPageDto {
        items = List.copyOf(Objects.requireNonNull(items, "items는 필수입니다."));
    }
}
