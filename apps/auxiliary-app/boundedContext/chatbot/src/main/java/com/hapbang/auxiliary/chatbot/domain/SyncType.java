package com.hapbang.auxiliary.chatbot.domain;

/** 읽기용 사본의 종류 (#716). 싱크는 종류마다 따로 실행·기록한다. 회원·강의는 #717에서 더한다 */
public enum SyncType {

    /** 출연 (출연 #713 {@code AppearanceSyncDto}) */
    APPEARANCE,
    /** 공개된 출연 리뷰 (출연 #713 {@code AppearanceReviewSyncDto}) */
    APPEARANCE_REVIEW
}
