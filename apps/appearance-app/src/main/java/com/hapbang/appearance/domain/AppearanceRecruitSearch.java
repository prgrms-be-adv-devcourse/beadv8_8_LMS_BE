package com.hapbang.appearance.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 출연 모집 공개 목록의 검색 조건과 정렬. 조건은 모두 선택값이고(null이면 조건 없음) 함께 쓰면 모두 만족하는 모집글만 남는다.
 * 공개 목록의 기본 조건(모집 중이고 모집 마감 일시 전)은 여기에 없고 언제나 걸린다.
 * <p>
 * 만들 때 값을 정리하고 확인한다. 틀리면 {@code INVALID_REQUEST}(400)다.
 *
 * @param keyword       제목·콘텐츠 설명·호스트 닉네임 부분 일치(대소문자 무시). 앞뒤 공백을 지우고, 비면 null. 50자 이하, NUL 문자 불가
 * @param scheduledFrom 방송 예정 일시가 이 날짜(KST) 이후. 그 날짜를 포함한다
 * @param scheduledTo   방송 예정 일시가 이 날짜(KST) 이전. 그 날짜를 포함한다
 * @param minFee        1인당 출연료(HP)가 이 값 이상. 0 이상
 * @param maxFee        1인당 출연료(HP)가 이 값 이하. 0 이상
 * @param hasSeats      true면 남은 자리가 있는(성립 인원 &lt; 모집 인원) 모집글만. false는 조건 없음
 * @param sort          정렬. null이면 최신순
 */
public record AppearanceRecruitSearch(
        String keyword,
        LocalDate scheduledFrom,
        LocalDate scheduledTo,
        Long minFee,
        Long maxFee,
        boolean hasSeats,
        AppearanceRecruitSort sort
) {

    /** 검색어 최대 길이 (앞뒤 공백을 지운 뒤) */
    public static final int KEYWORD_MAX = 50;

    /** 날짜는 yyyy-MM-dd(연도 네 자리)만. 그 밖의 연도는 DB 시각 범위를 벗어날 수 있어 받지 않는다 */
    private static final int MIN_YEAR = 1;
    private static final int MAX_YEAR = 9999;

    /** 조건 없음, 최신순 (지금까지의 공개 목록과 같다) */
    public static final AppearanceRecruitSearch NONE = new AppearanceRecruitSearch(null, null, null, null, null, false,
            AppearanceRecruitSort.LATEST);

    /**
     * @throws AppearanceException 검색어 50자 초과·NUL 문자, 연도가 네 자리가 아닌 날짜, 음수 출연료, 뒤집힌 날짜·출연료 범위
     *                             ({@code INVALID_REQUEST})
     */
    public AppearanceRecruitSearch {
        keyword = normalize(keyword);
        if (keyword != null && keyword.length() > KEYWORD_MAX) {
            throw invalid("검색어는 %d자 이하여야 합니다.".formatted(KEYWORD_MAX));
        }
        // PostgreSQL text는 NUL(0x00)을 받지 못해 조회가 500으로 끝나므로 미리 400으로 막는다
        if (keyword != null && keyword.indexOf('\0') >= 0) {
            throw invalid("검색어에 쓸 수 없는 문자(NUL)가 있습니다.");
        }
        validateDate(scheduledFrom);
        validateDate(scheduledTo);
        if (scheduledFrom != null && scheduledTo != null && scheduledFrom.isAfter(scheduledTo)) {
            throw invalid("방송 날짜 범위의 시작(scheduledFrom)이 끝(scheduledTo)보다 늦습니다.");
        }
        validateFee(minFee);
        validateFee(maxFee);
        if (minFee != null && maxFee != null && minFee > maxFee) {
            throw invalid("최소 출연료(minFee)가 최대 출연료(maxFee)보다 큽니다.");
        }
        if (sort == null) {
            sort = AppearanceRecruitSort.LATEST;
        }
    }

    /** 방송 예정 일시의 하한(포함): 시작 날짜 0시. 조건이 없으면 null */
    public LocalDateTime scheduledAtFrom() {
        return scheduledFrom == null ? null : scheduledFrom.atStartOfDay();
    }

    /** 방송 예정 일시의 상한(제외): 끝 날짜 다음 날 0시. 끝 날짜 하루 전체를 포함한다. 조건이 없으면 null */
    public LocalDateTime scheduledAtBefore() {
        return scheduledTo == null ? null : scheduledTo.plusDays(1).atStartOfDay();
    }

    private static String normalize(String keyword) {
        if (keyword == null) {
            return null;
        }
        String stripped = keyword.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static void validateDate(LocalDate date) {
        if (date != null && (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR)) {
            throw invalid("방송 날짜는 yyyy-MM-dd 형식(0001~9999년)이어야 합니다.");
        }
    }

    private static void validateFee(Long fee) {
        if (fee != null && fee < 0) {
            throw invalid("출연료 조건(minFee·maxFee)은 0 이상이어야 합니다.");
        }
    }

    private static AppearanceException invalid(String detail) {
        return new AppearanceException(AppearanceErrorCode.INVALID_REQUEST, detail);
    }
}
