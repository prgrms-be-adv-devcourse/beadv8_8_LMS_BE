package com.hapbang.auxiliary.chatbot.domain;

import java.util.List;
import java.util.Objects;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 회원 사본 한 행에 넣을 값 (#717). 가입·변경 이벤트와 회원 싱크가 같은 {@code UserDto}를 주고, app의 {@code CopyMapper}가 만든다.
 * <ul>
 *     <li>닉네임은 저장하지 않는다(추천·LLM에 필요 없음). 해시에는 넣는다(받은 DTO 전체로 계산).</li>
 *     <li>version은 비어 있을 수 있다({@code UserDto} 규칙). 판정은 {@link CopyOutcome}이 한다.</li>
 *     <li>마스킹한 회원은 분야·자기소개를 버린다({@link #withoutProfile()}). 해시는 받은 DTO 그대로다.</li>
 * </ul>
 *
 * @param categories 콘텐츠 분야 코드, 회원이 고른 순서(없으면 빈 목록)
 * @param bio        자기소개(없으면 null)
 * @param hash       받은 DTO에서 version을 뺀 값의 해시
 */
public record MemberCopy(
        long id,
        Role role,
        UserStatus status,
        List<String> categories,
        String bio,
        Long version,
        String hash
) {

    public MemberCopy {
        // 이벤트와 싱크가 같은 확인을 거친다. 사본 테이블의 role·status는 NOT NULL이다
        Objects.requireNonNull(role, "user.role은 필수입니다.");
        Objects.requireNonNull(status, "user.status는 필수입니다.");
        categories = List.copyOf(categories);
    }

    /** 마스킹한 회원: 분야·자기소개를 비운 값 */
    public MemberCopy withoutProfile() {
        return new MemberCopy(id, role, status, List.of(), null, version, hash);
    }
}
