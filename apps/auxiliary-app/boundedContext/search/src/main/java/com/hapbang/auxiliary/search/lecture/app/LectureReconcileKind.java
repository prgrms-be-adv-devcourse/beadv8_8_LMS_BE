package com.hapbang.auxiliary.search.lecture.app;

/**
 * 강의 검색 보정이 본 항목의 결과(#884, 지표 {@code kind}). 보정(놓친 이벤트를 바로잡음)인 것은 {@link #isFix()}이고 항목마다 INFO를 남긴다.
 */
public enum LectureReconcileKind {

    /** 강의 보정: 문서가 없던 강의를 넣었다(놓친 등록) */
    INSERTED("inserted", true),
    /** 강의 보정: 옛 version 문서를 고쳤다(놓친 수정·판매 중지·삭제) */
    OVERWRITTEN("overwritten", true),
    /** 강의 보정: 원본에 없는 강의의 문서를 숨겼다 */
    HIDDEN("hidden", true),
    /** 리뷰·판매자 보정: 사본에 새 version을 반영했다(놓친 리뷰·회원 이벤트). 판매자는 강의 문서가 바뀌지 않은 경우다 */
    COPIED("copied", true),
    /** 리뷰 보정: 원본에 없는 리뷰를 사본에서 지웠다 */
    REVIEW_DELETED("review_deleted", true),
    /** 판매자 칸이 회원 사본과 달라 고쳤다(판매자 보정·칸 맞추기) */
    SELLER_FIXED("seller_fixed", true),
    /** 평점 칸이 리뷰 사본 집계와 달라 고쳤다(칸 맞추기. 평점 칸 경합으로 남은 옛 값 포함) */
    RATING_FIXED("rating_fixed", true),
    /** 이미 같거나 더 새 값이었다(이벤트가 반영함) */
    ALREADY_APPLIED("already_applied", false),
    /** 원본 정보가 빠졌거나 ES가 그 값 때문에 거절해 건너뛰었다(멈추면 회차가 영영 끝나지 않는다) */
    SKIPPED("skipped", false);

    private final String tag;
    private final boolean fix;

    LectureReconcileKind(String tag, boolean fix) {
        this.tag = tag;
        this.fix = fix;
    }

    /** 지표·로그 값 */
    public String tag() {
        return tag;
    }

    /** 놓친 변경을 바로잡은 것인지 */
    public boolean isFix() {
        return fix;
    }
}
