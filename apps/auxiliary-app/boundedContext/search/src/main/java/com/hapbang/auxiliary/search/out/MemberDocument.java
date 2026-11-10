package com.hapbang.auxiliary.search.out;

/**
 * 처음 적재·재색인(#773)이 새 index에 넣는 정상 인플루언서 한 명.
 *
 * @param userId  회원 ID(문서 ID)
 * @param version 회원 version. 모르면 null
 * @param profile 프로필
 */
public record MemberDocument(long userId, Long version, MemberProfile profile) {
}
