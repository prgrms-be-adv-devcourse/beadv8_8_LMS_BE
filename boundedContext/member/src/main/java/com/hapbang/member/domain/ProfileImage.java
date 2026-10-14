package com.hapbang.member.domain;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 프로필 사진 규칙: jpeg·png·webp, 1바이트~5MB. S3 key는 member/profile/{회원ID}/{uuid} */
public final class ProfileImage {

    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    public static final long MAX_SIZE = 5L * 1024 * 1024;

    private static final String KEY_PREFIX = "member/profile/";

    private ProfileImage() {
    }

    /** 업로드 요청 검사 (본문을 읽기 전에 헤더로). Content-Length가 없으면(-1) 크기 오류 */
    public static void validate(String contentType, long contentLength) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(baseType(contentType))) {
            throw new MemberException(MemberErrorCode.INVALID_PROFILE_IMAGE_TYPE);
        }
        if (contentLength < 1 || contentLength > MAX_SIZE) {
            throw new MemberException(MemberErrorCode.INVALID_PROFILE_IMAGE_SIZE);
        }
    }

    /** 새 S3 key. 올릴 때마다 새로 만들어 예전 사진을 덮어쓰지 않는다 (예전 파일은 S3에 그대로 남는다) */
    public static String newKey(Long userId) {
        return KEY_PREFIX + userId + "/" + UUID.randomUUID();
    }

    /** "image/JPEG; charset=…"처럼 대소문자·파라미터가 달라도 같은 형식으로 본다 */
    public static String baseType(String contentType) {
        int semicolon = contentType.indexOf(';');
        String type = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return type.strip().toLowerCase(Locale.ROOT);
    }
}
