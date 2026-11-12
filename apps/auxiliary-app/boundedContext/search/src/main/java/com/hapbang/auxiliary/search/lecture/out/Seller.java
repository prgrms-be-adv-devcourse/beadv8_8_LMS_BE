package com.hapbang.auxiliary.search.lecture.out;

/**
 * 강의 문서에 넣는 판매자 정보(회원 사본 {@code search.member_copy} 한 행, #882 Q1).
 *
 * @param userId   회원 ID(강의의 {@code sellerUserId})
 * @param nickname 닉네임. 없으면 {@code null}
 * @param active   정상(ACTIVE) 회원인지. 아니면 그 판매자의 강의를 검색에서 뺀다(L8, Q2)
 */
public record Seller(long userId, String nickname, boolean active) {
}
