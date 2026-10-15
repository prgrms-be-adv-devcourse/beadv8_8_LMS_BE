package com.hapbang.appearance.in;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.appearance.app.dto.AppearanceResDto;
import com.hapbang.appearance.app.dto.AppearanceSummaryResDto;
import com.hapbang.appearance.domain.AppearanceRole;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.in.dto.HostCancelAppearanceReqDto;
import com.hapbang.appearance.in.dto.RejectAppearanceCancelReqDto;
import com.hapbang.appearance.in.dto.RequestAppearanceCancelReqDto;
import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 출연(성립 건) API Swagger 명세. 매핑은 {@link ApiV1AppearanceController}
 * <p>
 * 성공 응답은 {@link RsData}의 {@code data}에 담는다. 오류는 공통 핸들러의 Problem Detail이다.
 */
@Tag(name = "출연", description = """
        출연 요청 수락·모집 선정으로 성립한 출연. 내 출연 조회와 게스트의 출연 취소 요청, 호스트의 수락(합의 취소)·거절.
        정상 상태의 인플루언서 이상만 쓸 수 있다 (관리자 제외). 출연은 호스트·게스트만 볼 수 있다.""")
public interface ApiV1AppearanceSpec {

    String STATUS_FILTER = "상태 필터. 여러 번 보낼 수 있다 (예: status=ESTABLISHED&status=CANCEL_REQUESTED). 생략하면 전체";

    @Operation(summary = "내 출연 목록", description = """
            `role=HOST`면 내가 호스트인 출연, `role=GUEST`면 내가 게스트인 출연. 최신순 고정, 페이지는 page·size(기본 20, 최대 100).
            제목은 출처(요청서·모집글)의 제목이다.""")
    RsData<Page<AppearanceSummaryResDto>> getMine(Long userId,
            @Parameter(description = "HOST 호스트로 참여 / GUEST 게스트로 참여", required = true) AppearanceRole role,
            @Parameter(description = STATUS_FILTER) List<AppearanceStatus> statuses,
            @Parameter(hidden = true) Pageable pageable);

    @Operation(summary = "출연 상세", description = """
            호스트·게스트만 볼 수 있다 (다른 회원·없는 출연은 404 `APPEARANCE_NOT_FOUND`). 출연 취소 요청 이력(최대 2건)을 함께 담는다.""")
    RsData<AppearanceResDto> getDetail(Long userId, Long appearanceId);

    @Operation(summary = "출연 취소 요청 (게스트)", description = """
            성립한 출연(`ESTABLISHED`)을 방송 예정 일시 전까지 사유와 함께 취소 요청한다. 출연은 `CANCEL_REQUESTED`가 된다.
            - 사유 필수 500자 이하 (없으면 400 `INVALID_REQUEST`). 보낸 요청은 철회할 수 없다
            - 출연 1건당 2번까지, 다시 요청은 거절된 뒤에만 (넘으면 409 `CANCEL_REQUEST_LIMIT_EXCEEDED`, 처리 중이면 409 `CANCEL_REQUEST_ALREADY_PENDING`)
            - 게스트가 아니면 403 `NOT_APPEARANCE_GUEST`, 방송 예정 일시가 지났으면 409 `APPEARANCE_SCHEDULE_PASSED`,
              성립 상태가 아니면(합의 취소됨 등) 409 `APPEARANCE_NOT_CANCELABLE`
            - 호스트가 방송 예정 일시까지 응답하지 않으면 자동 거절된다""")
    RsData<AppearanceResDto> requestCancel(Long userId, Long appearanceId,
                                           @Valid RequestAppearanceCancelReqDto request);

    @Operation(summary = "출연 취소 요청 수락 (호스트)", description = """
            처리 중인 취소 요청을 수락해 합의 취소한다 (위약금 없음). 출연은 `CANCELED_BY_AGREEMENT`.
            - HP: 출연 요청이면 출연료 전액 반환. 모집이면 모집글이 마감 전일 때 성립 인원만 1 줄고 보류는 유지(빈자리에 다른 지원자 수락 가능),
              마감 뒤면 그 자리 출연료를 반환. 반환이 늦어져도 수락은 성공으로 응답한다
            - 호스트가 아니면 403 `NOT_APPEARANCE_HOST`, 처리 중인 요청이 없으면 409 `CANCEL_REQUEST_NOT_PENDING`,
              방송 예정 일시가 지났으면 409 `APPEARANCE_SCHEDULE_PASSED`""")
    RsData<AppearanceResDto> acceptCancel(Long userId, Long appearanceId);

    @Operation(summary = "출연 취소 요청 거절 (호스트)", description = """
            처리 중인 취소 요청을 거절한다. 출연은 `ESTABLISHED`로 유지되고, 게스트는 1번 더 요청할 수 있다.
            거절 사유는 선택(500자 이하)이며 요청 본문 없이 보내도 된다. 오류는 수락과 같다.""")
    RsData<AppearanceResDto> rejectCancel(Long userId, Long appearanceId, @Valid RejectAppearanceCancelReqDto request);

    @Operation(summary = "호스트 일방 취소", description = """
            성립한 출연을 방송 예정 일시 전에 호스트가 취소한다. 출연은 `CANCELED_BY_HOST`.
            - 게스트에게 위약금(출연료의 30%, 원 단위 미만 버림)을 지급하고 나머지는 호스트에게 반환한다. 정산이 늦어져도 취소는 성공으로 응답하고,
              진행 상태는 `hpPenaltyStatus`로 확인한다
            - 게스트의 출연 취소 요청이 처리 중이면 `confirmPendingCancelRequest: true` 없이는 409 `CANCEL_REQUEST_CONFIRM_REQUIRED`.
              확인하고 취소하면 그 요청은 `CLOSED_BY_HOST_CANCEL`로 끝나고, 게스트에게 위약금이 지급된다
            - 사유는 선택(500자 이하), 요청 본문 없이 보내도 된다
            - 호스트가 아니면 403 `NOT_APPEARANCE_HOST`, 성립·취소 요청 중이 아니면 409 `APPEARANCE_NOT_CANCELABLE`,
              방송 예정 일시가 지났으면 409 `APPEARANCE_SCHEDULE_PASSED`
            - 모집에서 성립한 출연이면 그 자리는 다시 열리지 않는다 (보류가 위약금·반환으로 정산됨)""")
    RsData<AppearanceResDto> hostCancel(Long userId, Long appearanceId, @Valid HostCancelAppearanceReqDto request);
}
