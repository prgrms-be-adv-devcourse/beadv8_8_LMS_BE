package com.hapbang.lecture.in;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.global.response.RsData;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.lecture.domain.LectureEarningStatus;
import com.hapbang.shared.lecture.dto.LectureEarningItemDto;
import com.hapbang.shared.lecture.dto.LectureEarningSummaryDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "강의 판매 수익 (내부)", description = "모듈 간 내부 API. billing이 회원의 지급 예정 수익을 보여 줄 때 호출한다 "
        + "(docs/api/internal-earnings.md). 회원 세션으로는 호출할 수 없고, 서버 밖에서는 nginx가 404로 막는다.")
public interface ApiV1LectureEarningInternalSpec {

    String API_KEY_DESCRIPTION = "내부 API 키 (hapbang.internal-api.key). 없거나 틀리면 401(본문 없음)";

    String STATUS_DESCRIPTION = """
            수익 상태 (여러 개 가능, 생략하면 아직 지급하지 않은 다섯 상태)
            - `CONFIRMATION_PENDING`: 구매 확정 대기. 구매 취소될 수 있다
            - `SCHEDULED`: 지급 예정. 구매 확정, 지급 예정 시각 전
            - `ON_HOLD`: 지급 중단(이의 제기 접수 중)
            - `PROCESSING`: 처리 중. 지급 예정 시각이 지나 다음 지급 배치(매일 01:00)에서 지급(일시 실패 뒤 재시도 포함)
            - `FAILED`: 지급 실패. 지급이 격리되어 관리자 조치 필요
            - `COMPLETED`: 지급 완료
            - `CANCELED`: 구매 취소 또는 이의 제기 인정 반환""";

    @Operation(summary = "판매 수익 요약",
            description = "판매자(userId)의 아직 지급하지 않은 수익을 상태별 {amountHp, count}로 합한다. 페이지와 관계없이 전체 항목 "
                    + "기준이고 지급 완료·구매 취소·반환은 빠진다. 판매 기록이 없으면 모두 0이다. asOf는 집계 기준 시각(+09:00). "
                    + "오류: userId 없음 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<LectureEarningSummaryDto> getSummary(
            @Parameter(description = "판매자 회원 ID (호출한 모듈이 인증한 회원)", required = true) Long userId);

    @Operation(summary = "판매 수익 목록",
            description = "판매자(userId)의 수익을 주문 항목 단위로 보여 준다. 상태 변경 최신순(statusUpdatedAt desc, sourceId desc)으로 "
                    + "고정한다(sort 무시). page·size(최대 100). payoutScheduledAt은 ON_HOLD·FAILED·CANCELED면 null이다. "
                    + "오류: userId 없음·잘못된 status 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<Page<LectureEarningItemDto>> getEarnings(
            @Parameter(description = "판매자 회원 ID (호출한 모듈이 인증한 회원)", required = true) Long userId,
            @Parameter(description = STATUS_DESCRIPTION) Set<LectureEarningStatus> statuses,
            Pageable pageable);
}
