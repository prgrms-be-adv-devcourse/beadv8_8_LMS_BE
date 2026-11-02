package com.hapbang.member.domain;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 프로필 사진 규칙: jpeg·png·webp, 1바이트~5MB. S3 key는 member/profile/{회원ID}/{uuid} */
public final class ProfileImage {

    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    public static final long MAX_SIZE = 5L * 1024 * 1024;

    /** 형식을 판별할 앞부분 길이 (WebP가 가장 길다: "RIFF" + 크기 4바이트 + "WEBP") */
    public static final int SIGNATURE_LENGTH = 12;

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

    /**
     * 파일 앞부분(매직 넘버)이 헤더의 형식과 맞는지. 헤더만 image/png로 바꾼 다른 파일을 막는다 (validate 뒤에 부른다).
     * JPEG FF D8 FF / PNG 89 50 4E 47 0D 0A 1A 0A / WebP "RIFF" + 크기 4바이트 + "WEBP"
     */
    public static void validateSignature(String contentType, byte[] head) {
        boolean matches = switch (baseType(contentType)) {
            case "image/jpeg" -> startsWith(head, 0, 0xFF, 0xD8, 0xFF);
            case "image/png" -> startsWith(head, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/webp" -> startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P');
            default -> false;
        };
        if (!matches) {
            throw new MemberException(MemberErrorCode.PROFILE_IMAGE_CONTENT_MISMATCH);
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

    /** head의 offset 위치부터 expected 바이트가 차례로 있는지 (파일이 짧으면 false) */
    private static boolean startsWith(byte[] head, int offset, int... expected) {
        if (head.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((head[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
