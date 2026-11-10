package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.config.LectureOrderProperties;
import com.hapbang.lecture.domain.OrderCouponWork;
import com.hapbang.lecture.out.OrderCouponWorkRepository;
import com.hapbang.lecture.out.coupon.CouponApiClient;

import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 작업 재전송 배치의 처리. 다음 시도 시각이 지난 {@code PENDING} 예약·해제 작업을 오래 기다린 것부터 정한 수까지 골라
 * 같은 workId·같은 내용으로 다시 보낸다. 트랜잭션 밖에서 실행한다(보내기와 결과 기록은 예약·해제 UseCase가 나눠 한다).
 * <ul>
 *     <li>예약은 {@link OrderCouponReserveUseCase}가 주문 흐름과 똑같이 처리한다. 성공이면 보류 작업을 등록하고, 보류는 HP 작업
 *     재시도 배치가 실행한다.</li>
 *     <li>해제는 {@link OrderCouponReleaseUseCase}가 처리한다.</li>
 *     <li>시도 한도에 닿은 작업은 보내지 않고 {@code DEAD}로 멈춘다(각 UseCase).</li>
 *     <li>쿠폰 회로가 열려 있으면 작업을 고르지 않고, 회차 도중에 열리면 남은 작업은 다음 회차로 넘긴다. 열린 동안 시도 횟수를 쓰지 않는다.</li>
 *     <li>작업 하나가 예외로 실패해도 다음 작업으로 넘어간다. 그 작업은 다음 회차에 다시 고른다.</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderCouponWorkUseCase {

    /** 쿠폰 작업을 다시 보내다 예기치 않은 예외가 났을 때 붙이는 alert 표식. 그 작업은 다음 회차에 다시 고른다 */
    static final String RETRY_FAILED_LOG = "[LECTURE_COUPON_WORK_RETRY_FAILED]";

    private final OrderCouponWorkRepository orderCouponWorkRepository;
    private final OrderCouponReserveUseCase orderCouponReserveUseCase;
    private final OrderCouponReleaseUseCase orderCouponReleaseUseCase;
    private final CouponApiClient couponApiClient;
    private final LectureOrderProperties properties;
    private final Clock clock;

    public OrderCouponWorkUseCase(OrderCouponWorkRepository orderCouponWorkRepository,
                                  OrderCouponReserveUseCase orderCouponReserveUseCase,
                                  OrderCouponReleaseUseCase orderCouponReleaseUseCase,
                                  CouponApiClient couponApiClient,
                                  LectureOrderProperties properties,
                                  @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.orderCouponWorkRepository = orderCouponWorkRepository;
        this.orderCouponReserveUseCase = orderCouponReserveUseCase;
        this.orderCouponReleaseUseCase = orderCouponReleaseUseCase;
        this.couponApiClient = couponApiClient;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return 보내기를 시도한 작업 수. 쿠폰 회로가 열려 넘긴 작업은 세지 않는다 */
    public int runDue() {
        if (!couponApiClient.canCall()) {
            log.debug("쿠폰 회로가 열려 쿠폰 작업을 다시 보내지 않는다");
            return 0;
        }
        List<OrderCouponWork> works = orderCouponWorkRepository.findDue(LocalDateTime.now(clock),
                PageRequest.of(0, properties.couponWorkBatchSize()));
        int sent = 0;
        for (OrderCouponWork work : works) {
            if (!couponApiClient.canCall()) {
                log.warn("쿠폰 회로가 열려 남은 쿠폰 작업은 다음 회차에 보낸다 remaining={}", works.size() - sent);
                break;
            }
            try {
                switch (work.getKind()) {
                    case RESERVE -> orderCouponReserveUseCase.reserve(work.getOrderId());
                    case RELEASE -> orderCouponReleaseUseCase.release(work.getOrderId());
                }
            } catch (RuntimeException unexpected) {
                log.error(RETRY_FAILED_LOG + " 쿠폰 작업을 다시 보내다 예외가 나 다음 회차에 다시 고른다 orderId={}, workId={}",
                        work.getOrderId(), work.getWorkId(), unexpected);
            }
            sent++;
        }
        return sent;
    }
}
