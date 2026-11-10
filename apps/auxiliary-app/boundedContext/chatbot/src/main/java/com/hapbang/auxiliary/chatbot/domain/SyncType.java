package com.hapbang.auxiliary.chatbot.domain;

/** 읽기용 사본의 종류 (#716, #717). 싱크는 종류마다 따로 실행·기록한다 */
public enum SyncType {

    /** 출연 (출연 #713 {@code AppearanceSyncDto}) */
    APPEARANCE,
    /** 공개된 출연 리뷰 (출연 #713 {@code AppearanceReviewSyncDto}) */
    APPEARANCE_REVIEW,
    /** 회원 (회원 {@code UserDto}, 내부 회원 목록 API, #717). 회원은 하드 삭제가 없어 원본 없음을 표시하지 않는다 */
    MEMBER,
    /** 강의 (강의 #712 {@code LectureSyncDto}, #717) */
    LECTURE,
    /** 구매 = 주문 항목 (강의 {@code LecturePurchaseSyncDto}, #717) */
    LECTURE_PURCHASE,
    /** 장바구니 항목 (강의 {@code CartItemSyncDto}, #717) */
    CART_ITEM,
    /** 강의 리뷰 별점 (강의 {@code LectureReviewSyncDto}, #717) */
    LECTURE_REVIEW
}
