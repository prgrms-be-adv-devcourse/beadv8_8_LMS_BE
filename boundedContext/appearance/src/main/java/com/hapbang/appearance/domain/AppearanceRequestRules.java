package com.hapbang.appearance.domain;

import java.util.regex.Pattern;

/**
 * 출연 요청서·모집글 입력 값의 길이·형식 제한. 도메인({@link AppearanceRequest}, {@link AppearanceRecruit})과
 * 요청 DTO의 Bean Validation이 같이 쓴다.
 * <p>
 * 길이는 DB 컬럼(마이그레이션)과 맞춘다: 제목 VARCHAR(100), 호스트 채널 URL VARCHAR(255), 콘텐츠 설명 TEXT.
 * 정책 수치(최저 출연료 등)가 아니라 형식 제한이라 {@link AppearancePolicy}가 아닌 여기에 둔다.
 */
public final class AppearanceRequestRules {

    /** 제목: 공백만으로는 안 되고 100자 이하 */
    public static final int TITLE_MAX = 100;

    /** 호스트 채널 URL: http:// 또는 https://로 시작, 공백 없음, 255자 이하 */
    public static final int URL_MAX = 255;
    public static final String URL_PATTERN = "^https?://\\S+$";

    /** 콘텐츠 설명: 공백만으로는 안 되고 5,000자 이하 */
    public static final int CONTENT_MAX = 5000;

    /** 예상 출연 시간(분): 1분 ~ 1,440분(24시간) */
    public static final int EXPECTED_MINUTES_MIN = 1;
    public static final int EXPECTED_MINUTES_MAX = 1440;

    /** 거절 사유: 선택 입력, 500자 이하 (DB 컬럼 VARCHAR(500)) */
    public static final int REJECT_REASON_MAX = 500;

    /** 공백만 있는 값 금지 (수정 요청에서 null은 허용하고, 값이 있으면 공백만으로는 안 된다) */
    public static final String NOT_BLANK_PATTERN = "(?s).*\\S.*";

    private static final Pattern URL = Pattern.compile(URL_PATTERN);

    private AppearanceRequestRules() {
    }

    static void validateTitle(String title) {
        if (isBlank(title) || title.length() > TITLE_MAX) {
            throw invalid("제목은 공백일 수 없고 %d자 이하여야 합니다.".formatted(TITLE_MAX));
        }
    }

    static void validateHostChannelUrl(String hostChannelUrl) {
        if (hostChannelUrl == null || hostChannelUrl.length() > URL_MAX || !URL.matcher(hostChannelUrl).matches()) {
            throw invalid("호스트 채널 URL은 http:// 또는 https://로 시작하고 %d자 이하여야 합니다.".formatted(URL_MAX));
        }
    }

    static void validateContent(String content) {
        if (isBlank(content) || content.length() > CONTENT_MAX) {
            throw invalid("콘텐츠 설명은 공백일 수 없고 %,d자 이하여야 합니다.".formatted(CONTENT_MAX));
        }
    }

    static void validateExpectedMinutes(int expectedMinutes) {
        if (expectedMinutes < EXPECTED_MINUTES_MIN || expectedMinutes > EXPECTED_MINUTES_MAX) {
            throw invalid("예상 출연 시간은 %d분 이상 %,d분 이하여야 합니다.".formatted(EXPECTED_MINUTES_MIN,
                    EXPECTED_MINUTES_MAX));
        }
    }

    static void validateRejectReason(String reason) {
        if (reason != null && reason.length() > REJECT_REASON_MAX) {
            throw invalid("거절 사유는 %d자 이하여야 합니다.".formatted(REJECT_REASON_MAX));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static AppearanceException invalid(String detail) {
        return new AppearanceException(AppearanceErrorCode.INVALID_REQUEST, detail);
    }
}
