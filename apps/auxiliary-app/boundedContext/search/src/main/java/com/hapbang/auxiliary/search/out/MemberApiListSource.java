package com.hapbang.auxiliary.search.out;

import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.out.MemberApiClient;

/**
 * 회원 내부 API로 회원 목록을 읽는다(#773, D62). Client는 통계 동기화와 같은 {@link MemberApiClient}다(인플루언서 {@code MemberApiStatsSource}).
 */
@Component
@Import(MemberApiClient.class)
class MemberApiListSource implements MemberListSource {

    private final MemberApiClient client;

    MemberApiListSource(MemberApiClient client) {
        this.client = client;
    }

    @Override
    public MemberPageDto members(Long afterId, int size) {
        return client.getMembers(afterId, size);
    }
}
