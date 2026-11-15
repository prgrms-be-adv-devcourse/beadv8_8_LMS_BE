package com.hapbang.auxiliary.notification.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 알림 값(`jsonb`)의 공통 키(ND-020·ND-051). 종류별 키는 그 종류의 템플릿이 정한다.
 * <ul>
 *     <li>{@value #VERSION}: 값 형식 버전(필수)</li>
 *     <li>{@value #NICKNAMES}: 문구에 들어가는 다른 회원의 닉네임. 키는 회원 ID 문자열이다. 이 키들이 언급 회원 ID가 된다</li>
 * </ul>
 */
public final class NotificationPayload {

    public static final String VERSION = "v";
    public static final String NICKNAMES = "nicknames";

    /** 파기된 회원의 닉네임 접두사. 회원 앱의 `User.MASKED_NICKNAME_PREFIX`와 같은 값이다(ND-036) */
    public static final String MASKED_NICKNAME_PREFIX = "탈퇴회원#";

    private NotificationPayload() {
    }

    /** 값의 회원 ID별 닉네임. 없으면 빈 맵 */
    public static Map<String, String> nicknames(Map<String, Object> payload) {
        Object raw = payload == null ? null : payload.get(NICKNAMES);
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> nicknames = new LinkedHashMap<>();
        map.forEach((key, value) -> nicknames.put(String.valueOf(key), value == null ? null : String.valueOf(value)));
        return Collections.unmodifiableMap(nicknames);
    }

    /** 파기된 회원의 닉네임 */
    public static String maskedNickname(long userId) {
        return MASKED_NICKNAME_PREFIX + userId;
    }
}
