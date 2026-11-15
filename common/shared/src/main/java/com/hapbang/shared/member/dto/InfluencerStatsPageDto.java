package com.hapbang.shared.member.dto;

import java.util.List;
import java.util.Objects;

/**
 * 인플루언서 통계 한 페이지 (#769). 정상 인플루언서(인플루언서·인증된 인플루언서 + ACTIVE)를 회원 ID 오름차순 키셋으로 준다.
 *
 * @param items       이 페이지의 인플루언서 통계
 * @param nextAfterId 다음 페이지를 부를 때 넘길 afterId. 마지막 페이지면 null
 */
public record InfluencerStatsPageDto(List<InfluencerStatsDto> items, Long nextAfterId) {

    /** 한 번에 받을 수 있는 최대 인플루언서 수 */
    public static final int MAX_SIZE = 500;

    // 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다. null 항목도 받지 않는다 (MemberPageDto와 같은 방식)
    public InfluencerStatsPageDto {
        items = List.copyOf(Objects.requireNonNull(items, "items는 필수입니다."));
    }
}
