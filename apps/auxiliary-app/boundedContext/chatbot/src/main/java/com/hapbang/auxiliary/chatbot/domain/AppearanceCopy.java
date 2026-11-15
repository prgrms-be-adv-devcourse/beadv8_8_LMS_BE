package com.hapbang.auxiliary.chatbot.domain;

import java.time.LocalDateTime;

import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;

/**
 * 출연 사본 한 행에 넣을 값 (#716). 출연 변경 이벤트와 출연 싱크가 같은 {@link AppearanceSyncDto}를 주므로 둘 다 여기서 만든다.
 * 공개 결과({@code result})는 출연이 줄인 값을 그대로 쓰고 해석하지 않는다.
 *
 * @param hash 받은 DTO에서 version을 뺀 값의 해시({@link CopyHash})
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

    public static AppearanceCopy from(AppearanceSyncDto dto) {
        LocalDateTime scheduledAt = CopyTimes.kst(dto.scheduledAt());
        String hash = CopyHash.of(new Hashed(dto.id(), dto.hostId(), dto.guestId(), dto.result(), scheduledAt));
        return new AppearanceCopy(dto.id(), dto.hostId(), dto.guestId(), dto.result(), scheduledAt, dto.version(), hash);
    }

    /** 해시에 넣는 값. version은 넣지 않는다 */
    private record Hashed(Long id, Long hostId, Long guestId, AppearanceResult result, LocalDateTime scheduledAt) {
    }
}
