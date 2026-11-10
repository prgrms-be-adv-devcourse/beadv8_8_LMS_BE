package com.hapbang.auxiliary.chatbot.domain;

import java.util.List;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 회원 사본 한 행에 넣을 값 (#717). 가입·변경 이벤트와 회원 싱크가 같은 {@link UserDto}를 준다.
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
        categories = List.copyOf(categories);
    }

    public static MemberCopy from(UserDto dto) {
        String hash = CopyHash.of(new Hashed(dto.id(), dto.nickname(), dto.role(), dto.status(), dto.contentCategories(),
                dto.bio()));
        return new MemberCopy(dto.id(), dto.role(), dto.status(), dto.contentCategories(), dto.bio(), dto.version(), hash);
    }

    /** 마스킹한 회원: 분야·자기소개를 비운 값 */
    public MemberCopy withoutProfile() {
        return new MemberCopy(id, role, status, List.of(), null, version, hash);
    }

    /** 해시에 넣는 값. version은 넣지 않는다 */
    private record Hashed(Long id, String nickname, Role role, UserStatus status, List<String> contentCategories,
            String bio) {
    }
}
