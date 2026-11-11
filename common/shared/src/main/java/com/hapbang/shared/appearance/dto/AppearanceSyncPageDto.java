package com.hapbang.shared.appearance.dto;

import java.util.List;
import java.util.Objects;

/**
 * 출연 동기화 내부 API 한 페이지 (#713). 출연 ID 오름차순 키셋 페이지다(회원 {@code MemberPageDto}와 같은 모양).
 *
 * @param items       이 페이지의 출연 (모든 상태). 이벤트와 같은 {@link AppearanceSyncDto}다
 * @param nextAfterId 다음 페이지를 부를 때 넘길 afterId. 마지막 페이지면 null
 */
public record AppearanceSyncPageDto(List<AppearanceSyncDto> items, Long nextAfterId) {

    /** 한 번에 받을 수 있는 최대 건수 */
    public static final int MAX_SIZE = 500;

    // 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다. null 항목도 받지 않는다
    public AppearanceSyncPageDto {
        items = List.copyOf(Objects.requireNonNull(items, "items는 필수입니다."));
    }
}
