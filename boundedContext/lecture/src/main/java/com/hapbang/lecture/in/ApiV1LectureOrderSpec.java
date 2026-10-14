package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.global.response.RsData;
import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.app.dto.OrderSummaryResponse;
import com.hapbang.lecture.app.dto.PurchasedLectureResponse;
import com.hapbang.lecture.in.dto.OrderCreateRequest;
import com.hapbang.lecture.in.dto.SaleResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 강의 주문 API Swagger 명세 (설계 8.5). 매핑은 {@link ApiV1LectureOrderController} */
@Tag(name = "강의 주문", description = "주문(HP 결제), 구매 확정, 구매 취소, 구매·판매 내역")
public interface ApiV1LectureOrderSpec {

    @Operation(summary = "주문",
            description = "장바구니 주문 또는 바로 구매(강의 1개). HP 보류까지 끝난 뒤 응답하며, 잔액이 부족하면 주문이 만들어지지 않는다. "
                    + "판매 중이 아닌 강의, 본인 강의, 이미 보유 중인 강의, 같은 강의 중복은 주문할 수 없다. 주문한 강의는 장바구니에서 빠진다.")
    RsData<OrderResponse> createOrder(Long userId, OrderCreateRequest request);

    @Operation(summary = "내 주문 목록", description = "최근 주문부터.")
    RsData<Page<OrderSummaryResponse>> getMyOrders(Long userId, Pageable pageable);

    @Operation(summary = "주문 상세", description = "주문 상태와 항목별 상태·기한. 이의 제기 문의에는 여기서 받은 orderItemId를 담는다.")
    RsData<OrderResponse> getOrder(Long userId, Long orderId);

    @Operation(summary = "구매 확정 (직접)", description = "본인 항목, PURCHASED → CONFIRMED(DIRECT). 확정 후에는 구매 취소할 수 없다.")
    RsData<OrderItemResponse> confirm(Long userId, Long orderId, Long orderItemId);

    @Operation(summary = "재생 동의",
            description = "본인 항목, PURCHASED → CONFIRMED(FIRST_PLAY). 첫 재생·자료 다운로드 전에 프론트가 동의서"
                    + "(\"재생하면 구매가 확정되어 단순 변심으로 구매 취소할 수 없습니다\")를 보여 주고 동의하면 호출한다. "
                    + "이후 콘텐츠 시청 URL을 발급받을 수 있다. 자동 구매 확정 시각(autoConfirmAt)이 이미 지났으면 "
                    + "직접 확정과 같이 CONFIRMED(AUTO), confirmedAt = autoConfirmAt으로 기록한다. "
                    + "이미 CONFIRMED·PAID_OUT이면 바꾸지 않고 200으로 항목을 응답한다. "
                    + "오류: 남의 주문·없는 주문 404 ORDER_NOT_FOUND, 주문에 없는 항목 404 ORDER_ITEM_NOT_FOUND, "
                    + "구매 취소·반환된 항목 409 ORDER_ITEM_NOT_PURCHASED.")
    RsData<OrderItemResponse> consentToPlay(Long userId, Long orderId, Long orderItemId);

    @Operation(summary = "구매 취소",
            description = "본인 항목, PURCHASED이고 autoConfirmAt 전, 이의 제기 없음. HP 반환까지 끝난 뒤 응답한다. "
                    + "모든 항목이 끝나면 주문도 CANCELED가 된다.")
    RsData<OrderItemResponse> cancel(Long userId, Long orderId, Long orderItemId);

    @Operation(summary = "시청 가능한 강의",
            description = "유저·인플루언서·인증된 인플루언서. 항목이 PURCHASED·CONFIRMED·PAID_OUT인 강의를 최근 구매순으로 보여 준다. "
                    + "판매자가 삭제한 강의도 포함한다(lectureDeleted). 제목·썸네일은 현재 강의 기준이다. "
                    + "itemStatus가 PURCHASED면 영상·자료를 보기 전에 재생 동의가 필요하다.")
    RsData<Page<PurchasedLectureResponse>> getPurchasedLectures(Long userId, Pageable pageable);

    @Operation(summary = "판매 내역", description = "인증된 인플루언서. 판매한 주문 항목의 상태와 지급 예정 시각.")
    RsData<Page<SaleResponse>> getSales(Long userId, Pageable pageable);
}
