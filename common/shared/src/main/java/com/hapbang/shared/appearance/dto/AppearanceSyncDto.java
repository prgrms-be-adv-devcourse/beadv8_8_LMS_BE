package com.hapbang.shared.appearance.dto;

import java.time.LocalDateTime;
import java.util.Objects;

import com.hapbang.shared.appearance.domain.AppearanceResult;

/**
 * 출연 하나의 동기화용 사본 (#713). 출연 변경 이벤트({@code AppearanceChangedEvent})와 출연 동기화 내부 API가 같은 값을 싣는다.
 * 금액·사유·출처(요청·모집)는 싣지 않는다. 시각은 offset 없는 KST {@code LocalDateTime}이다.
 * <p>
 * 같은 {@code id}·{@code version}이면 이벤트와 내부 API 값이 같다. 받는 쪽은 {@code version}이 큰 것만 반영한다.
 *
 * @param id          출연 ID
 * @param hostId      호스트 회원 ID
 * @param guestId     게스트 회원 ID
 * @param result      공개 결과
 * @param scheduledAt 방송 예정 일시
 * @param version     출연 버전. 상태가 바뀔 때마다 오른다 (성립 직후 0)
 */
public record AppearanceSyncDto(
        Long id,
        Long hostId,
        Long guestId,
        AppearanceResult result,
        LocalDateTime scheduledAt,
        Long version
) {

    // 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다
    public AppearanceSyncDto {
        Objects.requireNonNull(id, "id는 필수입니다.");
        Objects.requireNonNull(hostId, "hostId는 필수입니다.");
        Objects.requireNonNull(guestId, "guestId는 필수입니다.");
        Objects.requireNonNull(result, "result는 필수입니다.");
        Objects.requireNonNull(scheduledAt, "scheduledAt은 필수입니다.");
        Objects.requireNonNull(version, "version은 필수입니다.");
    }
}
