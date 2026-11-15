package com.hapbang.member.out;

/**
 * 회원의 프로필 사진 S3 key (#770 일괄 조회용 조회 결과).
 *
 * @param userId 회원 ID
 * @param key    S3 key (모듈 기준, member/profile/{회원ID}/{uuid})
 */
public record ProfileImageKey(Long userId, String key) {
}
