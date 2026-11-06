package com.hapbang.lecture.in;

import com.hapbang.lecture.global.response.RsData;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.lecture.dto.CartItemSyncPageDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

@Tag(name = "강의 동기화 (내부)", description = "모듈 간 내부 API. aux-app AI context가 주 1회 처음부터 끝까지 읽어 이벤트 누락을 바로잡는다 "
        + "(docs/api/internal-lecture-sync.md, #712). 항목은 같은 종류의 이벤트와 같은 DTO다. 회원 로그인으로는 호출할 수 없고, "
        + "내부 포트로만 받는다(외부 포트로 오면 404).")
// 검증 조건은 구현 클래스가 아니라 여기(인터페이스)에 둔다. 구현에서 다시 선언하면 Bean Validation이 거부한다
public interface ApiV1LectureSyncInternalSpec {

    String API_KEY_DESCRIPTION = "내부 API 키 (hapbang.internal-api.key). 없거나 틀리면 401(본문 없음)";
    String AFTER_ID_DESCRIPTION = "이 ID보다 큰 항목부터 (생략하면 처음부터, 0 이상)";
    String SIZE_DESCRIPTION = "페이지 크기 (1~500, 기본 100)";
    String PAGING = " ID 오름차순으로 size개씩 준다. 다음 페이지는 응답의 nextAfterId를 afterId로 넘기고, nextAfterId가 없으면 "
            + "마지막 페이지다. 오류: size가 1~500 밖이거나 afterId가 음수면 400 INVALID_REQUEST.";

    @Operation(summary = "강의 동기화 목록 (키셋)",
            description = "모든 상태의 강의(검수 대기·반려·삭제 포함)를 LectureChangedEvent와 같은 LectureSyncDto로 준다. "
                    + "추천해도 되는지는 onSale, 콘텐츠는 삭제되지 않은 것만 순서대로다." + PAGING)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<LectureSyncPageDto> getLectures(
            @Parameter(description = AFTER_ID_DESCRIPTION) @PositiveOrZero Long afterId,
            @Parameter(description = SIZE_DESCRIPTION) @Min(1) @Max(LectureSyncPageDto.MAX_SIZE) int size);

    @Operation(summary = "구매 동기화 목록 (키셋)",
            description = "모든 주문 항목을 LecturePurchaseChangedEvent와 같은 LecturePurchaseSyncDto로 준다(ID = 주문 항목 ID). "
                    + "result는 OWNED·PENDING(보유, 추천 제외)·CANCELED·RETURNED·FAILED이고, 결제 전이면 purchasedAt이 없다." + PAGING)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<LecturePurchaseSyncPageDto> getPurchases(
            @Parameter(description = AFTER_ID_DESCRIPTION) @PositiveOrZero Long afterId,
            @Parameter(description = SIZE_DESCRIPTION) @Min(1) @Max(LecturePurchaseSyncPageDto.MAX_SIZE) int size);

    @Operation(summary = "장바구니 항목 동기화 목록 (키셋)",
            description = "뺀 항목(deleted=true)을 포함한 모든 장바구니 항목을 CartItemChangedEvent와 같은 CartItemSyncDto로 준다." + PAGING)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<CartItemSyncPageDto> getCartItems(
            @Parameter(description = AFTER_ID_DESCRIPTION) @PositiveOrZero Long afterId,
            @Parameter(description = SIZE_DESCRIPTION) @Min(1) @Max(CartItemSyncPageDto.MAX_SIZE) int size);

    @Operation(summary = "리뷰 동기화 목록 (키셋)",
            description = "지운 리뷰(deleted=true)를 포함한 모든 리뷰를 LectureReviewChangedEvent와 같은 LectureReviewSyncDto로 준다. "
                    + "counted는 강의 평균 평점·리뷰 목록에 반영되는지다(반환 처리 중·반환된 주문 항목의 리뷰는 false)." + PAGING)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<LectureReviewSyncPageDto> getReviews(
            @Parameter(description = AFTER_ID_DESCRIPTION) @PositiveOrZero Long afterId,
            @Parameter(description = SIZE_DESCRIPTION) @Min(1) @Max(LectureReviewSyncPageDto.MAX_SIZE) int size);
}
