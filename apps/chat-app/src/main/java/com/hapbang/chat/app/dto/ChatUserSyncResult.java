package com.hapbang.chat.app.dto;

/**
 * 회원 정보 한 건을 채팅 회원 복제본에 반영한 결과. 반영 여부와 반영 뒤 현재 상태를 함께 돌려준다(설계 문서 5.3).
 *
 * @param userId        회원 ID
 * @param outcome       생성·갱신·건너뜀 구분
 * @param memberVersion 처리 뒤 복제본이 가진 회원 원본 버전. 버전 없는 정보만 받았으면 null
 * @param canChat       처리 뒤 복제본 기준으로 지금 채팅할 수 있는지
 */
public record ChatUserSyncResult(Long userId, Outcome outcome, Long memberVersion, boolean canChat) {

    public enum Outcome {
        /** 복제본을 새로 만들었다 (가입 이벤트·백필) */
        CREATED,
        /** 더 새로운 회원 버전이라 기존 복제본에 반영했다 (변경 이벤트·백필) */
        UPDATED,
        /** 이미 복제본이 있어 가입 이벤트를 건너뛰었다 (같은 가입 이벤트의 재처리) */
        SKIPPED_EXISTING,
        /** 이미 반영한 버전보다 새롭지 않아 건너뛰었다 (늦게 온·중복된 정보) */
        SKIPPED_STALE
    }

    /** 기존 복제본을 이번 처리로 갱신했는지. 연결 재검사 작업은 이때만 남긴다 */
    public boolean updated() {
        return outcome == Outcome.UPDATED;
    }
}
