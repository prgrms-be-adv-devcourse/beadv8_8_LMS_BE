package com.hapbang.lecture.fake;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hapbang.lecture.out.coupon.CouponCallResult;
import com.hapbang.lecture.out.coupon.CouponApiClient;
import com.hapbang.lecture.out.coupon.LectureCouponClientProperties;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.shared.coupon.api.CouponPriceRaiseBlockedResponse;
import com.hapbang.shared.coupon.api.CouponReleaseRequest;
import com.hapbang.shared.coupon.api.CouponReleaseResponse;
import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.shared.coupon.api.CouponReserveResponse;

/**
 * 강의 모듈 테스트용 쿠폰 내부 API Client. HTTP를 부르지 않는다. 상태 변환은 {@code CouponApiClientTest}가 맡는다.
 * <ul>
 *     <li>{@link #respond}로 다음 호출의 결과를 차례로 정한다. 정한 결과가 없으면 계산은 할인 없는 성공, 가격 인상 제한은 막히지 않음이고,
 *     그 밖의 호출은 결과 불명이다.</li>
 *     <li>호출마다 요청과 그때 강의 트랜잭션이 열려 있었는지를 남긴다.</li>
 *     <li>{@link #setCircuitOpen}으로 쿠폰 회로가 열린 상태를 흉내 낸다({@link #canCall()}만 바뀐다).</li>
 * </ul>
 * 컨텍스트가 테스트 사이에 재사용되므로 테스트마다 {@link #reset()}한다. 빈은 {@link FakeCouponApiClientConfig}가 실제 Client보다 우선해 등록한다.
 */
public class FakeCouponApiClient extends CouponApiClient {

    private final Deque<Function<Object, CouponCallResult<?>>> responses = new ArrayDeque<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile boolean circuitOpen;

    public FakeCouponApiClient() {
        super(null, LectureCouponClientProperties.defaults());
    }

    public synchronized void reset() {
        responses.clear();
        calls.clear();
        circuitOpen = false;
    }

    /** 쿠폰 회로가 열린 상태를 흉내 낸다. 기본은 닫혀 있다 */
    public void setCircuitOpen(boolean circuitOpen) {
        this.circuitOpen = circuitOpen;
    }

    @Override
    public boolean canCall() {
        return !circuitOpen;
    }

    /** 다음 호출의 결과. 요청을 받아 결과를 만든다 */
    public synchronized void respond(Function<Object, CouponCallResult<?>> response) {
        responses.addLast(response);
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public CouponCallResult<CouponCalculateResponse> calculate(CouponCalculateRequest request) {
        return answer(request, () -> new CouponCallResult.Succeeded<>(noDiscount(request)));
    }

    @Override
    public CouponCallResult<CouponReserveResponse> reserve(CouponReserveRequest request) {
        return answer(request, () -> new CouponCallResult.Unknown<>(0, "정한 결과가 없다"));
    }

    @Override
    public CouponCallResult<CouponReleaseResponse> release(CouponReleaseRequest request) {
        return answer(request, () -> new CouponCallResult.Unknown<>(0, "정한 결과가 없다"));
    }

    @Override
    public CouponCallResult<CouponReservationStatusResponse> reservationStatus(String workId, long memberId,
                                                                              long orderId) {
        return answer(workId, () -> new CouponCallResult.Unknown<>(0, "정한 결과가 없다"));
    }

    @Override
    public CouponCallResult<CouponPriceRaiseBlockedResponse> priceRaiseBlocked(long lectureId) {
        return answer(lectureId, () -> new CouponCallResult.Succeeded<>(
                new CouponPriceRaiseBlockedResponse(lectureId, false)));
    }

    /** 요청한 강의마다 할인이 없는 계산 결과 */
    public static CouponCalculateResponse noDiscount(CouponCalculateRequest request) {
        List<CouponCalculateResponse.Item> items = request.items().stream()
                .map(item -> new CouponCalculateResponse.Item(item.lectureId(), item.price(), 0, 0, 0, item.price(),
                        item.price(), List.of()))
                .toList();
        long total = request.items().stream().mapToLong(CouponCalculateRequest.Item::price).sum();
        return new CouponCalculateResponse(false, false, items, 0, total);
    }

    @SuppressWarnings("unchecked")
    private <T> CouponCallResult<T> answer(Object request, Supplier<CouponCallResult<T>> fallback) {
        calls.add(new Call(request, TransactionSynchronizationManager.isActualTransactionActive()));
        Function<Object, CouponCallResult<?>> response;
        synchronized (this) {
            response = responses.pollFirst();
        }
        return response == null ? fallback.get() : (CouponCallResult<T>) response.apply(request);
    }

    /**
     * 받은 호출 하나.
     *
     * @param request           요청(계산·예약·해제는 요청 record, 예약 상태는 workId, 가격 인상 제한은 강의 ID)
     * @param transactionActive 그때 강의 트랜잭션이 열려 있었는지
     */
    public record Call(Object request, boolean transactionActive) {
    }
}
