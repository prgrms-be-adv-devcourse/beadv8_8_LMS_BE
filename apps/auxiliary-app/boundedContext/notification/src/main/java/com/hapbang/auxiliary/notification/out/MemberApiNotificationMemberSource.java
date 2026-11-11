package com.hapbang.auxiliary.notification.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.NotificationMemberSource;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.out.MemberApiClient;

/**
 * 회원 목록 내부 API로 Backfill할 회원을 읽는다(ND-077, D62). Client는 검색·챗봇과 같은 {@link MemberApiClient}다. 회원 API 주소가
 * 없으면 만들지 않는다(모듈 테스트). 그때 Backfill은 실행할 수 없다.
 */
@Component
@ConditionalOnProperty(prefix = "hapbang.clients.member", name = "base-url")
@Import(MemberApiClient.class)
class MemberApiNotificationMemberSource implements NotificationMemberSource {

    private final MemberApiClient client;

    MemberApiNotificationMemberSource(MemberApiClient client) {
        this.client = client;
    }

    @Override
    public MemberPageDto members(Long afterId, int size) {
        return client.getMembers(afterId, size);
    }
}
