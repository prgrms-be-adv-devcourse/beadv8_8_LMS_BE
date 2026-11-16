package com.hapbang.auxiliary.chatbot.domain;

import java.time.LocalDateTime;

import com.hapbang.shared.appearance.domain.AppearanceResult;

/**
 * 출연 사본 한 행에 넣을 값 (#716). 출연 변경 이벤트와 출연 싱크가 같은 DTO를 주므로 둘 다 app의 {@code CopyMapper}가 만든다.
 * 공개 결과({@code result})는 출연이 줄인 값을 그대로 쓰고 해석하지 않는다.
 *
 * @param hash 받은 DTO에서 version을 뺀 값의 해시({@code CopyHash})
 */
public record AppearanceCopy(
        long id,
        long hostId,
        long guestId,
        AppearanceResult result,
        LocalDateTime scheduledAt,
        long version,
        String hash
) {
}
