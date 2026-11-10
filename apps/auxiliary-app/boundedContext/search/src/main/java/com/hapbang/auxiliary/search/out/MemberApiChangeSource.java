package com.hapbang.auxiliary.search.out;

import java.time.LocalDateTime;

import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.shared.member.dto.MemberChangePageDto;
import com.hapbang.shared.member.out.MemberApiClient;

/**
 * 회원 내부 API로 변경분을 읽는다(#772, D62). Client는 통계 동기화와 같은 {@link MemberApiClient}다({@link MemberApiStatsSource}).
 */
@Component
@ConditionalOnSearchEnabled
@Import(MemberApiClient.class)
class MemberApiChangeSource implements MemberChangeSource {

    private final MemberApiClient client;

    MemberApiChangeSource(MemberApiClient client) {
        this.client = client;
    }

    @Override
    public MemberChangePageDto changes(LocalDateTime afterUpdatedAt, long afterId, int size) {
        return client.getChanges(afterUpdatedAt, afterId, size);
    }
}
