package com.hapbang.auxiliary.search.influencer.out;

import com.hapbang.shared.member.dto.ContentCategoriesDto;
import com.hapbang.shared.member.dto.InfluencerStatsPageDto;

/**
 * 검색이 회원 앱에서 읽는 것(#771): 인플루언서 통계(#769)와 콘텐츠 분야 목록. 테스트는 가짜로 바꿔 끼운다.
 * 실패는 {@code MemberApiCallException}(shared)으로 나간다.
 */
public interface MemberStatsSource {

    /** 정상 인플루언서 통계 한 페이지(회원 ID 오름차순 키셋). afterId가 null이면 처음부터 */
    InfluencerStatsPageDto influencerStats(Long afterId, int size);

    /** 콘텐츠 분야 전체(꺼진 분야 포함) */
    ContentCategoriesDto contentCategories();
}
