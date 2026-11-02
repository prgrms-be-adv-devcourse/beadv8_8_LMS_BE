package com.hapbang.shared.appearance.dto;

/**
 * 출연에서 회원이 맡은 역할. 다른 모듈에 넘기는 출연 정보(리뷰 공개 이벤트 등)에 쓴다 (#513).
 */
public enum AppearanceParticipantRole {
    /** 출연 요청을 보냈거나 모집글을 올린 쪽 */
    HOST,
    /** 출연 요청을 받았거나 모집에서 수락된 쪽 */
    GUEST
}
