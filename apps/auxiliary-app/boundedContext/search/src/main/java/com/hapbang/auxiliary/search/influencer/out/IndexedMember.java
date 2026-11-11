package com.hapbang.auxiliary.search.influencer.out;

import java.util.List;
import java.util.Objects;

/**
 * 검색 문서에 지금 들어 있는 회원 이벤트 필드(#772). 증분 보정이 반영 전에 읽어, 받은 회원 정보와 내용이 실제로 다른지 가른다(#772 Q1).
 * 통계(채널·팔로워·평점)와 분야 이름은 회원 정보가 아니라 넣지 않는다.
 *
 * @param memberVersion 문서의 회원 version. 없으면 null
 * @param hidden        숨김(tombstone)인지
 * @param nickname      닉네임. 숨긴 문서는 null
 * @param bio           자기소개
 * @param role          역할
 * @param categoryCodes 콘텐츠 분야 코드(회원이 고른 순서)
 */
public record IndexedMember(Long memberVersion, boolean hidden, String nickname, String bio, String role,
                            List<String> categoryCodes) {

    public IndexedMember {
        categoryCodes = categoryCodes == null ? List.of() : categoryCodes.stream().filter(Objects::nonNull).toList();
    }
}
