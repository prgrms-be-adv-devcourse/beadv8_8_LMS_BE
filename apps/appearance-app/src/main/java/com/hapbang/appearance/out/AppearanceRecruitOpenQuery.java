package com.hapbang.appearance.out;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.hapbang.appearance.domain.AppearanceBasicInfo;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitSearch;
import com.hapbang.appearance.domain.AppearanceRecruitSort;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.AppearanceUser;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * 출연 모집 공개 목록의 조회 조건({@link Specification})과 정렬({@link Sort}). 검색 조건은 값이 있을 때만 붙는다.
 * <ul>
 *     <li>언제나 모집 중이고 모집 마감 일시가 기준 시각 뒤인 모집글만 본다. 마감 처리 전이어도 마감 일시가 지났으면 빠진다.</li>
 *     <li>검색어는 제목·콘텐츠 설명·호스트 닉네임(출연 모듈에 복제한 회원 정보 {@link AppearanceUser})에서 부분 일치로 찾고,
 *     대소문자를 무시한다. {@code %}·{@code _}·{@code \}는 글자 그대로 찾는다.</li>
 *     <li>정렬은 {@link AppearanceRecruitSort}로만 정한다. 기준 값이 같으면 최신순이다.</li>
 * </ul>
 */
public final class AppearanceRecruitOpenQuery {

    /** LIKE 검색어의 이스케이프 문자 */
    private static final char ESCAPE = '\\';

    /** 최신순. 같은 시각에 만든 글은 ID로 순서를 고정한다 */
    private static final Sort LATEST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private AppearanceRecruitOpenQuery() {
    }

    /** 공개 목록 조건: 모집 중, 모집 마감 일시가 {@code now} 뒤, 그리고 값이 있는 검색 조건 모두 */
    public static Specification<AppearanceRecruit> where(LocalDateTime now, AppearanceRecruitSearch search) {
        return (recruit, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(recruit.get("status"), AppearanceRecruitStatus.RECRUITING));
            predicates.add(cb.greaterThan(recruit.<LocalDateTime>get("recruitDeadline"), now));
            if (search.keyword() != null) {
                predicates.add(keywordMatches(recruit, query, cb, search.keyword()));
            }
            if (search.scheduledAtFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(recruit.<LocalDateTime>get("scheduledAt"),
                        search.scheduledAtFrom()));
            }
            if (search.scheduledAtBefore() != null) {
                predicates.add(cb.lessThan(recruit.<LocalDateTime>get("scheduledAt"), search.scheduledAtBefore()));
            }
            if (search.minFee() != null) {
                predicates.add(cb.greaterThanOrEqualTo(recruit.<Long>get("feePerPerson"), search.minFee()));
            }
            if (search.maxFee() != null) {
                predicates.add(cb.lessThanOrEqualTo(recruit.<Long>get("feePerPerson"), search.maxFee()));
            }
            if (search.hasSeats()) {
                predicates.add(cb.lessThan(recruit.<Integer>get("selectedCount"), recruit.<Integer>get("recruitCount")));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** 정렬 기준. 기준 값이 같으면 최신순 */
    public static Sort orderOf(AppearanceRecruitSort sort) {
        return switch (sort) {
            case LATEST -> LATEST;
            case SCHEDULED_SOON -> Sort.by(Sort.Direction.ASC, "scheduledAt").and(LATEST);
            case DEADLINE_SOON -> Sort.by(Sort.Direction.ASC, "recruitDeadline").and(LATEST);
            case FEE_HIGH -> Sort.by(Sort.Direction.DESC, "feePerPerson").and(LATEST);
        };
    }

    /**
     * 제목·콘텐츠 설명·호스트 닉네임 중 하나에 검색어가 들어 있다. 호스트 닉네임은 같은 모듈의 복제 회원 테이블
     * ({@code appearances.users})을 모집글의 호스트 ID로 찾는 하위 쿼리다.
     */
    private static Predicate keywordMatches(Root<AppearanceRecruit> recruit, CriteriaQuery<?> query,
                                            CriteriaBuilder cb, String keyword) {
        String pattern = "%" + escape(keyword.toLowerCase(Locale.ROOT)) + "%";
        Path<AppearanceBasicInfo> basicInfo = recruit.get("basicInfo");

        Subquery<Long> hostNickname = query.subquery(Long.class);
        Root<AppearanceUser> host = hostNickname.from(AppearanceUser.class);
        hostNickname.select(host.<Long>get("id"))
                .where(cb.equal(host.get("id"), recruit.get("hostId")),
                        cb.like(cb.lower(host.<String>get("nickname")), pattern, ESCAPE));

        return cb.or(
                cb.like(cb.lower(basicInfo.<String>get("title")), pattern, ESCAPE),
                cb.like(cb.lower(basicInfo.<String>get("content")), pattern, ESCAPE),
                cb.exists(hostNickname));
    }

    /** LIKE 특수 문자(이스케이프 문자 자신, {@code %}, {@code _})를 글자 그대로 찾도록 앞에 이스케이프 문자를 붙인다 */
    private static String escape(String keyword) {
        StringBuilder escaped = new StringBuilder(keyword.length());
        for (char c : keyword.toCharArray()) {
            if (c == ESCAPE || c == '%' || c == '_') {
                escaped.append(ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
