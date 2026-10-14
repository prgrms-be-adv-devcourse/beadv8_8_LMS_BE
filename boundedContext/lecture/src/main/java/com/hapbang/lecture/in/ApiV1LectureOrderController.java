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
import com.hapbang.global.response.RsData;
import com.hapbang.lecture.app.LectureOrderFacade;
import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.app.dto.OrderSummaryResponse;
import com.hapbang.lecture.app.dto.PurchasedLectureResponse;
import com.hapbang.lecture.in.dto.OrderCreateRequest;
import com.hapbang.lecture.in.dto.SaleResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 강의 주문 API (설계 8.5). Swagger 명세는 {@link ApiV1LectureOrderSpec} */
// 성공은 global RsData로 감싸고, 오류는 global 공통 핸들러가 ProblemDetail로 응답한다 (D29). message는 구현할 때 정한다.
// 역할 검사(유저·인플루언서·인증된 인플루언서, 관리자 불가)는 LectureOrderFacade의 @PreAuthorize가 한다.
// TODO: 판매 내역은 #150에서 구현한다.
// TODO(security): /sales는 인증된 인플루언서만 허용한다. 구현할 때 Facade에 추가한다.
@RestController
@RequestMapping("/api/v1/lectures")
@RequiredArgsConstructor
public class ApiV1LectureOrderController implements ApiV1LectureOrderSpec {

    private final LectureOrderFacade lectureOrderFacade;

    @Override
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<OrderResponse> createOrder(@LoginUserId Long userId, @Valid @RequestBody OrderCreateRequest request) {
        return RsData.created("주문했습니다.", lectureOrderFacade.createOrder(userId, request.lectureIds()));
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
    public RsData<OrderItemResponse> cancel(@LoginUserId Long userId, @PathVariable("orderId") Long orderId,
            @PathVariable("orderItemId") Long orderItemId) {
        return RsData.ok("구매를 취소했습니다.", lectureOrderFacade.cancel(userId, orderId, orderItemId));
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
        throw NotImplemented.yet();
    }
}
