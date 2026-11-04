package com.hapbang.chat.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.hapbang.shared.member.dto.MemberPageDto;

/**
 * 기존 회원 백필 설정 ({@code hapbang.chat.backfill.*}, 설계 문서 5.4). 수치는 모두 미확정 초기값(Q12)이다.
 * 기본값은 이 코드에 둔다(application.yaml에 두지 않는다). 환경마다 다르게 써야 하면 같은 키의 yaml·환경 변수로 덮는다.
 *
 * @param pageSize            회원 내부 API 한 번에 받을 회원 수. 1~{@link MemberPageDto#MAX_SIZE}, 기본 500
 * @param maxLimit            한 번 실행에서 처리할 최대 회원 수. 기본 5,000
 * @param conflictMaxAttempts 회원 한 명을 반영하다 가입·변경 이벤트와 충돌했을 때 최대 시도 횟수. 기본 3
 */
@ConfigurationProperties("hapbang.chat.backfill")
public record ChatUserBackfillProperties(Integer pageSize, Integer maxLimit, Integer conflictMaxAttempts) {

    public static final int DEFAULT_PAGE_SIZE = MemberPageDto.MAX_SIZE;
    public static final int DEFAULT_MAX_LIMIT = 5_000;
    public static final int DEFAULT_CONFLICT_MAX_ATTEMPTS = 3;

    public ChatUserBackfillProperties {
        pageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        maxLimit = maxLimit == null ? DEFAULT_MAX_LIMIT : maxLimit;
        conflictMaxAttempts = conflictMaxAttempts == null ? DEFAULT_CONFLICT_MAX_ATTEMPTS : conflictMaxAttempts;
        if (pageSize < 1 || pageSize > MemberPageDto.MAX_SIZE) {
            throw new IllegalArgumentException("hapbang.chat.backfill.page-size는 1~" + MemberPageDto.MAX_SIZE + "이어야 합니다.");
        }
        if (maxLimit < 1) {
            throw new IllegalArgumentException("hapbang.chat.backfill.max-limit은 1 이상이어야 합니다.");
        }
        if (conflictMaxAttempts < 1) {
            throw new IllegalArgumentException("hapbang.chat.backfill.conflict-max-attempts는 1 이상이어야 합니다.");
        }
    }
}
