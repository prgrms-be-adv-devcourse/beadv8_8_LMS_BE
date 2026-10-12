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

import jakarta.validation.Valid;

/** 강의 주문 API (설계 8.5). Swagger 명세는 {@link ApiV1LectureOrderControllerDocs} */
// TODO: 13장 3단계(주문·직접 확정·구매 취소), 5단계(재생 동의)에서 구현한다.
// TODO(security): 유저·인플루언서·인증된 인플루언서만 허용한다(관리자 불가, 설계 9장). /sales는 인증된 인플루언서. global SecurityConfig와 함께 추가한다.
@RestController
@RequestMapping("/api/v1/lectures")
public class ApiV1LectureOrderController implements ApiV1LectureOrderControllerDocs {

    @Override
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(@LoginUserId Long userId, @Valid @RequestBody OrderCreateRequest request) {
        throw NotImplemented.yet();
    }

    @Override
    @GetMapping("/orders")
    public Page<OrderSummaryResponse> getMyOrders(@LoginUserId Long userId,
            Pageable pageable) {
        throw NotImplemented.yet();
    }

    @Override
    @GetMapping("/orders/{orderId}")
    public OrderResponse getOrder(@LoginUserId Long userId, @PathVariable("orderId") Long orderId) {
        throw NotImplemented.yet();
    }

    @Override
    @PostMapping("/orders/{orderId}/items/{orderItemId}/confirm")
    public OrderItemResponse confirm(@LoginUserId Long userId, @PathVariable("orderId") Long orderId,
            @PathVariable("orderItemId") Long orderItemId) {
        throw NotImplemented.yet();
    }

    @Override
    @PostMapping("/orders/{orderId}/items/{orderItemId}/play-consent")
    public OrderItemResponse consentToPlay(@LoginUserId Long userId, @PathVariable("orderId") Long orderId,
            @PathVariable("orderItemId") Long orderItemId) {
        throw NotImplemented.yet();
    }

    @Override
    @PostMapping("/orders/{orderId}/items/{orderItemId}/cancel")
    public OrderItemResponse cancel(@LoginUserId Long userId, @PathVariable("orderId") Long orderId,
            @PathVariable("orderItemId") Long orderItemId) {
        throw NotImplemented.yet();
    }

    @Override
    @GetMapping("/purchased")
    public Page<PurchasedLectureResponse> getPurchasedLectures(@LoginUserId Long userId,
            Pageable pageable) {
        throw NotImplemented.yet();
    }

    @Override
    @GetMapping("/sales")
    public Page<SaleResponse> getSales(@LoginUserId Long userId,
            Pageable pageable) {
        throw NotImplemented.yet();
    }
}
