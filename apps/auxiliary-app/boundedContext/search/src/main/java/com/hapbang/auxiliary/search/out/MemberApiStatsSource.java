package com.hapbang.auxiliary.search.out;

import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import com.hapbang.shared.member.dto.ContentCategoriesDto;
import com.hapbang.shared.member.dto.InfluencerStatsPageDto;
import com.hapbang.shared.member.out.MemberApiClient;

/**
 * 회원 내부 API로 통계·분야 목록을 읽는다(#771, D62). shared Client는 스캔되지 않으므로 여기서 {@link MemberApiClient}만 가져온다
 * (auxiliary-app README "다른 앱 내부 API"). 주소는 {@code hapbang.clients.member.base-url}({@code MEMBER_API_BASE_URL}).
 */
@Component
@Import(MemberApiClient.class)
class MemberApiStatsSource implements MemberStatsSource {

    private final MemberApiClient client;

    MemberApiStatsSource(MemberApiClient client) {
        this.client = client;
    }

    @Override
    public InfluencerStatsPageDto influencerStats(Long afterId, int size) {
        return client.getInfluencerStats(afterId, size);
    }

    @Override
    public ContentCategoriesDto contentCategories() {
        return client.getContentCategories();
    }
}
