package com.hapbang.auxiliary.search.influencer.out;

import java.time.Instant;
import java.util.Objects;

/**
 * 처음 적재·재색인(#773)이 새 index에 넣는 회원 한 명. 정상 인플루언서는 보이는 문서, 그 밖의 회원은 숨김 문서(tombstone)다. 숨김 문서도 넣어야
 * 교체 뒤 늦게 온 옛 이벤트가 숨긴 회원을 다시 보이게 하지 못한다(#773 리뷰 Q3, 이벤트 반영과 같은 보호).
 *
 * @param userId   회원 ID(문서 ID)
 * @param version  회원 version. 모르면 null
 * @param profile  보이는 문서의 프로필. 숨김 문서면 null
 * @param hiddenAt 숨김 문서의 숨긴 시각(7일 뒤 정리 기준). 보이는 문서면 null
 */
public record MemberDocument(long userId, Long version, MemberProfile profile, Instant hiddenAt) {

    public MemberDocument {
        if ((profile == null) == (hiddenAt == null)) {
            throw new IllegalArgumentException("보이는 문서는 프로필만, 숨김 문서는 숨긴 시각만 둡니다 userId=" + userId);
        }
    }

    /** 보이는 문서(정상 인플루언서) */
    public static MemberDocument visible(long userId, Long version, MemberProfile profile) {
        return new MemberDocument(userId, version, Objects.requireNonNull(profile, "profile은 필수입니다."), null);
    }

    /** 숨김 문서(정상 인플루언서가 아닌 회원) */
    public static MemberDocument hidden(long userId, Long version, Instant hiddenAt) {
        return new MemberDocument(userId, version, null, Objects.requireNonNull(hiddenAt, "hiddenAt은 필수입니다."));
    }

    /** 검색에 보이는 문서인지 */
    public boolean isVisible() {
        return profile != null;
    }
}
