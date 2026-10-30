package com.hapbang.appearance.app;

/** 회원 원본 스냅샷 하나를 반영한 결과 (관리자 Backfill 집계, #394) */
public enum AppearanceUserSyncResult {
    /** 출연 회원이 없어 만들었다 */
    CREATED,
    /** 회원 버전이 더 높아 갱신했다 */
    UPDATED,
    /** 이미 같거나 더 높은 버전을 반영해 그대로 두었다 */
    IGNORED
}
