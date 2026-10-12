package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.global.auth.LoginUserId;
import com.hapbang.lecture.in.dto.OrderCreateRequest;
import com.hapbang.lecture.in.dto.OrderItemResponse;
import com.hapbang.lecture.in.dto.OrderResponse;
import com.hapbang.lecture.in.dto.OrderSummaryResponse;
import com.hapbang.lecture.in.dto.PurchasedLectureResponse;
import com.hapbang.lecture.in.dto.SaleResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** 강의 주문 API (설계 8.5) */
// TODO: 13장 3단계(주문·직접 확정·구매 취소), 5단계(재생 동의)에서 구현한다.
// TODO(security): 유저·인플루언서·인증된 인플루언서만 허용한다(관리자 불가, 설계 9장). /sales는 인증된 인플루언서. global SecurityConfig와 함께 추가한다.
@Tag(name = "강의 주문", description = "주문(HP 결제), 구매 확정, 구매 취소, 구매·판매 내역")
@RestController
@RequestMapping("/api/v1/lectures")
public class ApiV1LectureOrderController {

    @Operation(summary = "주문",
            description = "장바구니 주문 또는 바로 구매(강의 1개). HP 보류까지 끝난 뒤 응답하며, 잔액이 부족하면 주문이 만들어지지 않는다. "
                    + "판매 중이 아닌 강의, 본인 강의, 이미 보유 중인 강의, 같은 강의 중복은 주문할 수 없다. 주문한 강의는 장바구니에서 빠진다.")
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(@LoginUserId Long userId, @Valid @RequestBody OrderCreateRequest request) {
        throw NotImplemented.yet();
    }

    @Operation(summary = "내 주문 목록", description = "최근 주문부터.")
    @GetMapping("/orders")
    public Page<OrderSummaryResponse> getMyOrders(@LoginUserId Long userId,
            Pageable pageable) {
        throw NotImplemented.yet();
    }

    @Operation(summary = "주문 상세", description = "주문 상태와 항목별 상태·기한. 이의 제기 문의에는 여기서 받은 orderItemId를 담는다.")
    @GetMapping("/orders/{orderId}")
    public OrderResponse getOrder(@LoginUserId Long userId, @PathVariable("orderId") Long orderId) {
        throw NotImplemented.yet();
    }

    @Operation(summary = "구매 확정 (직접)", description = "본인 항목, PURCHASED → CONFIRMED(DIRECT). 확정 후에는 구매 취소할 수 없다.")
    @PostMapping("/orders/{orderId}/items/{orderItemId}/confirm")
    public OrderItemResponse confirm(@LoginUserId Long userId, @PathVariable("orderId") Long orderId,
            @PathVariable("orderItemId") Long orderItemId) {
        throw NotImplemented.yet();
    }

    @Operation(summary = "재생 동의",
            description = "본인 항목, PURCHASED → CONFIRMED(FIRST_PLAY). 첫 재생·자료 다운로드 전에 프론트가 동의서"
                    + "(\"재생하면 구매가 확정되어 단순 변심으로 구매 취소할 수 없습니다\")를 보여 주고 동의하면 호출한다. "
                    + "이후 콘텐츠 시청 URL을 발급받을 수 있다.")
    @PostMapping("/orders/{orderId}/items/{orderItemId}/play-consent")
    public OrderItemResponse consentToPlay(@LoginUserId Long userId, @PathVariable("orderId") Long orderId,
            @PathVariable("orderItemId") Long orderItemId) {
        throw NotImplemented.yet();
    }

    @Operation(summary = "구매 취소",
            description = "본인 항목, PURCHASED이고 autoConfirmAt 전, 이의 제기 없음. HP 반환까지 끝난 뒤 응답한다. "
                    + "모든 항목이 끝나면 주문도 CANCELED가 된다.")
    @PostMapping("/orders/{orderId}/items/{orderItemId}/cancel")
    public OrderItemResponse cancel(@LoginUserId Long userId, @PathVariable("orderId") Long orderId,
            @PathVariable("orderItemId") Long orderItemId) {
        throw NotImplemented.yet();
    }

    @Operation(summary = "시청 가능한 강의", description = "항목이 PURCHASED·CONFIRMED·PAID_OUT인 강의. 삭제된 강의도 포함한다.")
    @GetMapping("/purchased")
    public Page<PurchasedLectureResponse> getPurchasedLectures(@LoginUserId Long userId,
            Pageable pageable) {
        throw NotImplemented.yet();
    }

    @Operation(summary = "판매 내역", description = "인증된 인플루언서. 판매한 주문 항목의 상태와 지급 예정 시각.")
    @GetMapping("/sales")
    public Page<SaleResponse> getSales(@LoginUserId Long userId,
            Pageable pageable) {
        throw NotImplemented.yet();
    }
}
