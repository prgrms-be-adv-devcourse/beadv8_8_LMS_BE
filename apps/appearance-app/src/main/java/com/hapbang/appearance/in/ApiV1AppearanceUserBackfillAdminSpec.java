package com.hapbang.appearance.in;

import com.hapbang.appearance.app.dto.AppearanceUserBackfillResDto;
import com.hapbang.appearance.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 관리자 기존 회원 Backfill API Swagger 명세 (#394). 매핑은 {@link ApiV1AppearanceUserBackfillAdminController}
 * <p>
 * 성공 응답은 {@link RsData}의 {@code data}에 담는다. 오류는 공통 핸들러의 Problem Detail이다.
 */
@Tag(name = "출연 회원 정보 동기화 (관리자)", description = """
        출연 회원(복제한 회원 정보)은 회원 가입 이벤트(Kafka)로 만든다. 회원 앱이 Kafka로 보내기 전에 가입한 회원은 출연 회원이 없어
        출연 API에서 `APPEARANCE_USER_NOT_FOUND`가 되므로, 관리자가 회원 내부 API로 채운다.
        관리자(`ADMIN`)만 쓸 수 있다 (그 밖의 로그인 회원은 403, 비로그인 401).""")
public interface ApiV1AppearanceUserBackfillAdminSpec {

    @Operation(summary = "기존 회원 Backfill (수동 실행)", description = """
            회원 내부 API에서 afterId보다 큰 회원을 ID 오름차순으로 최대 limit명 읽어(한 번에 500명씩) 회원 이벤트와 같은 규칙으로 반영한다.
            - 출연 회원이 없으면 만들고, 회원 버전이 더 높을 때만 갱신한다(같거나 낮은 버전은 무시). 이벤트로 반영한 최신 정보를 덮지 않고, 다시 실행해도 결과가 같다
            - 응답: scanned = created + updated + ignored + failed, failedIds(최대 100개), lastProcessedId,
              completed(회원 목록을 끝까지 읽었는지, 성공 여부는 failed로 판단). completed가 false면 nextAfterId를 다음 afterId로 넘겨 이어서 실행한다
            - 회원 한 명의 반영 실패는 failed로 세고 계속한다
            - 회원 내부 API 장애·응답 계약 위반은 실행을 멈추고 503 `MEMBER_SOURCE_UNAVAILABLE`(일시 장애·잘못된 응답·주소 미설정),
              `MEMBER_SOURCE_REJECTED`(내부 API가 거절)이다. 이미 반영한 회원은 유지하고 Problem Detail에 멈추기 전까지의 집계와
              `resumeAfterId`(이어서 실행할 afterId)를 담는다
            - 실행 중 다시 요청하면 409 `BACKFILL_ALREADY_RUNNING`
            - afterId 0 이상(기본 0), limit 1~1,000(기본 100). 범위 밖은 400 `INVALID_REQUEST`
            - Gateway 응답 제한(30초)을 넘기면 504를 받는다. 그래도 서버는 끝까지 처리하고 결과는 로그에만 남으며,
              끝나기 전에 다시 요청하면 409다. 잠시 뒤 같은 afterId로 다시 실행하면 이미 반영한 회원은 ignored로 지나간다""")
    RsData<AppearanceUserBackfillResDto> backfill(
            @Parameter(description = "이 회원 ID보다 큰 회원부터 (기본 0 = 처음부터)") @PositiveOrZero long afterId,
            @Parameter(description = "이번 실행에서 처리할 최대 회원 수 (1~1,000, 기본 100)") @Min(1) @Max(1_000) int limit);
}
