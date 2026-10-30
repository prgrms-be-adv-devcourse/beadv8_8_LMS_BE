package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.lecture.global.auth.LoginUserId;
import com.hapbang.lecture.global.response.RsData;
import com.hapbang.lecture.app.LectureOrderFacade;
import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.app.dto.OrderSummaryResponse;
import com.hapbang.lecture.app.dto.PurchasedLectureResponse;
import com.hapbang.lecture.app.dto.SaleResponse;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.in.dto.OrderCreateRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 강의 주문 API (설계 8.5). Swagger 명세는 {@link ApiV1LectureOrderSpec} */
// 성공은 global RsData로 감싸고, 오류는 global 공통 핸들러가 ProblemDetail로 응답한다 (D29). message는 구현할 때 정한다.
// 역할 검사(유저·인플루언서·인증된 인플루언서, 관리자 불가. 판매 내역은 인증된 인플루언서만)는 LectureOrderFacade의 @PreAuthorize가 한다.
@RestController
@RequestMapping("/api/v1/lectures")
@RequiredArgsConstructor
public class ApiV1LectureOrderController implements ApiV1LectureOrderSpec {

    private final LectureOrderFacade lectureOrderFacade;

    @Override
    @PostMapping("/orders")
    public ResponseEntity<RsData<OrderResponse>> createOrder(@LoginUserId Long userId,
                                                             @Valid @RequestBody OrderCreateRequest request) {
        OrderResponse order = lectureOrderFacade.createOrder(userId, request.lectureIds());
        // 결제가 끝나지 않았으면 처리 중 주문을 202로 돌려준다. 프론트는 다시 주문하지 않고 주문 상세를 조회한다 (D58 (4))
        RsData<OrderResponse> body = order.status() == LectureOrderStatus.PAID
                ? RsData.created("주문했습니다.", order)
                : RsData.of(HttpStatus.ACCEPTED, "주문 결제를 처리 중입니다.", order);
        return ResponseEntity.status(body.statusCode()).body(body);
    }

    @Override
    @GetMapping("/orders")
    public RsData<Page<OrderSummaryResponse>> getMyOrders(@LoginUserId Long userId,
            Pageable pageable) {
        return RsData.ok("주문 목록을 조회했습니다.", lectureOrderFacade.getOrders(userId, pageable));
    }

    @Override
    @GetMapping("/orders/{orderId}")
    public RsData<OrderResponse> getOrder(@LoginUserId Long userId, @PathVariable("orderId") Long orderId) {
        return RsData.ok("주문을 조회했습니다.", lectureOrderFacade.getOrder(userId, orderId));
    }

    @Override
    @PostMapping("/orders/{orderId}/items/{orderItemId}/confirm")
    public RsData<OrderItemResponse> confirm(@LoginUserId Long userId, @PathVariable("orderId") Long orderId,
            @PathVariable("orderItemId") Long orderItemId) {
        return RsData.ok("구매를 확정했습니다.", lectureOrderFacade.confirm(userId, orderId, orderItemId));
    }

    @Override
    @PostMapping("/orders/{orderId}/items/{orderItemId}/play-consent")
    public RsData<OrderItemResponse> consentToPlay(@LoginUserId Long userId, @PathVariable("orderId") Long orderId,
            @PathVariable("orderItemId") Long orderItemId) {
        return RsData.ok("재생에 동의했습니다.", lectureOrderFacade.consentToPlay(userId, orderId, orderItemId));
    }

    @Override
    @PostMapping("/orders/{orderId}/items/{orderItemId}/cancel")
    public ResponseEntity<RsData<OrderItemResponse>> cancel(@LoginUserId Long userId,
            @PathVariable("orderId") Long orderId, @PathVariable("orderItemId") Long orderItemId) {
        OrderItemResponse item = lectureOrderFacade.cancel(userId, orderId, orderItemId);
        // 반환이 끝나지 않았으면 구매 취소 처리 중 항목을 202로 돌려준다 (D59 (1))
        RsData<OrderItemResponse> body = item.status() == OrderItemStatus.CANCELED
                ? RsData.ok("구매를 취소했습니다.", item)
                : RsData.of(HttpStatus.ACCEPTED, "구매 취소를 처리 중입니다.", item);
        return ResponseEntity.status(body.statusCode()).body(body);
    }

    @Override
    @GetMapping("/purchased")
    public RsData<Page<PurchasedLectureResponse>> getPurchasedLectures(@LoginUserId Long userId,
            Pageable pageable) {
        return RsData.ok("시청 가능한 강의 목록을 조회했습니다.", lectureOrderFacade.getPurchasedLectures(userId, pageable));
    }

    @Override
    @GetMapping("/sales")
    public RsData<Page<SaleResponse>> getSales(@LoginUserId Long userId,
            Pageable pageable) {
        return RsData.ok("판매 내역을 조회했습니다.", lectureOrderFacade.getSales(userId, pageable));
    }
}
