package com.hapbang.chat.domain;

/** 단체방 초대 요청의 회원별 결과 */
public enum ChatGroupInviteResult {
    /** 이번 요청으로 참여했다(재초대 포함) */
    JOINED,
    /** 이미 참여 중이다. 다시 처리하지 않고 안내도 남기지 않는다 */
    ALREADY_MEMBER,
    /** 참여하지 못했다. 사유는 {@link ChatGroupInviteFailure}다 */
    FAILED
}
