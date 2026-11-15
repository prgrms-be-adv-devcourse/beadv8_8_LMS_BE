package com.hapbang.auxiliary.search.lecture.out;

/**
 * 강의 문서에 넣는 판매자 정보(회원 사본 {@code search.member_copy} 한 행, #882 Q1).
 *
 * @param userId   회원 ID(강의의 {@code sellerUserId})
 * @param nickname 닉네임. 없으면 {@code null}
 * @param active   정상(ACTIVE) 회원인지. 아니면 그 판매자의 강의를 검색에서 뺀다(L8, Q2)
 * @param version  회원 version. 강의 문서의 판매자 칸을 더 옛 값으로 되돌리지 않게 비교한다(PR #906 리뷰 Q12). 모르면 {@code null}
 */
public record Seller(long userId, String nickname, boolean active, Long version) {

    /** version을 모르는 판매자 정보 */
    public Seller(long userId, String nickname, boolean active) {
        this(userId, nickname, active, null);
    }
}
