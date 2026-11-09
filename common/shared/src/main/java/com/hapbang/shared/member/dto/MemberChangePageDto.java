package com.hapbang.shared.member.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 회원 변경분 한 페이지 (#769). users.updated_at이 커서보다 뒤인 회원을 (updatedAt, id) 오름차순으로 준다. 모든 역할·상태를 포함한다.
 * 커서 시각은 회원 앱 시간대(Asia/Seoul)의 시각이다. 받는 쪽은 값을 해석하지 않고 그대로 다음 요청에 넘긴다.
 * 늦게 커밋된 행이 이미 지나간 커서 뒤에 나타날 수 있으므로, 받는 쪽은 이어 읽을 때 커서를 1분 겹쳐 다시 읽고 version으로 거른다
 * (docs/api/internal-members.md 4장)
 *
 * @param items              이 페이지의 회원. 이벤트와 같은 UserDto라 version으로 옛 정보를 거를 수 있다
 * @param nextAfterUpdatedAt 다음 페이지를 부를 때 넘길 afterUpdatedAt. 마지막 페이지면 null
 * @param nextAfterId        다음 페이지를 부를 때 넘길 afterId. 마지막 페이지면 null
 */
public record MemberChangePageDto(List<UserDto> items, LocalDateTime nextAfterUpdatedAt, Long nextAfterId) {

    /** 한 번에 받을 수 있는 최대 회원 수 */
    public static final int MAX_SIZE = 500;

    // 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다. null 항목도 받지 않는다
    public MemberChangePageDto {
        items = List.copyOf(Objects.requireNonNull(items, "items는 필수입니다."));
    }
}
