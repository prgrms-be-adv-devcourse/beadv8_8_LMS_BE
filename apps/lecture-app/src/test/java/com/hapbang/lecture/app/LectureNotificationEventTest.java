package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.FileType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.out.FileResourceRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.lecture.domain.LectureConfirmationType;
import com.hapbang.shared.lecture.dto.LectureObjectionFileRequest;
import com.hapbang.shared.lecture.dto.LectureOrderItemDto;
import com.hapbang.shared.lecture.event.LectureApprovedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionFiledEvent;
import com.hapbang.shared.lecture.event.LectureOrderHoldFailedEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemCanceledEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemConfirmedEvent;
import com.hapbang.shared.lecture.event.LectureOrderPaidEvent;
import com.hapbang.shared.lecture.event.LectureRejectedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.billing.RealBillingTestFixture;

/**
 * 알림용 강의 이벤트(#872, D85)가 업무 흐름에서 상태가 바뀔 때 한 번씩, 커밋된 값으로 나가는지 Facade부터 DB까지 확인한다.
 * 기대 시각은 커밋 뒤 DB에서 다시 읽은 값이다. Kafka key·헤더는 {@code LectureSyncEventKafkaTest}가 확인한다.
 * 쿠폰 예약 거절로 결제에 실패하는 경로는 {@code OrderCouponReserveTest}가 확인한다.
 */
@IntegrationTest
// 강의는 인증된 인플루언서, 검수는 관리자, 주문은 구매자, 이의 제기 접수는 내부 진입점이다
@WithMockUser(roles = {"USER", "VERIFIED_INFLUENCER", "ADMIN", "INTERNAL"})
@RecordApplicationEvents
class LectureNotificationEventTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long INQUIRY_ID = 500L;
    private static final String REASON = "강의 소개와 영상 내용이 다릅니다.";

    @Autowired
    private LectureFacade lectureFacade;

    @Autowired
    private LectureInspectionFacade lectureInspectionFacade;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureObjectionFacade lectureObjectionFacade;

    @Autowired
    private OrderAutoConfirmUseCase orderAutoConfirmUseCase;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FileResourceRepository fileResourceRepository;

    @Autowired
    private RealBillingTestFixture billing;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents events;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.wallet(BUYER_ID, 1_000_000L, 0L);
        billing.wallet(SELLER_ID, 0L, 0L);
    }

    @Test
    void 검수를_승인하면_그_회차와_검수_시각으로_승인_이벤트가_한_번_나간다() {
        Long lectureId = register();

        lectureInspectionFacade.approve(ADMIN_ID, lectureId);

        Lecture approved = lectureRepository.findById(lectureId).orElseThrow();
        assertThat(events.stream(LectureApprovedEvent.class)).containsExactly(new LectureApprovedEvent(lectureId,
                SELLER_ID, TITLE, 1, kst(approved.getInspectedAt())));
        assertThat(events.stream(LectureRejectedEvent.class)).isEmpty();
    }

    @Test
    void 반려_뒤_재검수를_신청하면_회차가_올라_다음_검수_결과에_실린다() {
        Long lectureId = register();

        lectureInspectionFacade.reject(ADMIN_ID, lectureId, "영상 음질이 너무 낮습니다.");
        LocalDateTime firstRejectedAt = lectureRepository.findById(lectureId).orElseThrow().getInspectedAt();
        lectureFacade.requestInspection(SELLER_ID, lectureId);
        lectureInspectionFacade.reject(ADMIN_ID, lectureId, "자료가 빠졌습니다.");
        LocalDateTime secondRejectedAt = lectureRepository.findById(lectureId).orElseThrow().getInspectedAt();
        lectureFacade.requestInspection(SELLER_ID, lectureId);
        lectureInspectionFacade.approve(ADMIN_ID, lectureId);

        assertThat(events.stream(LectureRejectedEvent.class)).containsExactly(
                new LectureRejectedEvent(lectureId, SELLER_ID, TITLE, "영상 음질이 너무 낮습니다.", 1, kst(firstRejectedAt)),
                new LectureRejectedEvent(lectureId, SELLER_ID, TITLE, "자료가 빠졌습니다.", 2, kst(secondRejectedAt)));
        assertThat(events.stream(LectureApprovedEvent.class)).singleElement()
                .extracting(LectureApprovedEvent::inspectionSequence).isEqualTo(3);
    }

    @Test
    void 보류가_모두_성공하면_주문_항목_목록과_구매_시각으로_결제_완료_이벤트가_한_번_나간다() {
        Lecture first = saveOnSale("OBS 방송 세팅");
        Lecture second = saveOnSale("마이크 고르기");

        OrderResponse order = order(first, second);

        List<OrderItemResponse> items = order.items();
        assertThat(events.stream(LectureOrderPaidEvent.class)).containsExactly(new LectureOrderPaidEvent(
                order.orderId(), BUYER_ID, List.of(
                new LectureOrderItemDto(items.get(0).orderItemId(), first.getId(), "OBS 방송 세팅", PRICE_HP),
                new LectureOrderItemDto(items.get(1).orderItemId(), second.getId(), "마이크 고르기", PRICE_HP)),
                kst(order.paidAt())));
        assertThat(events.stream(LectureOrderHoldFailedEvent.class)).isEmpty();
    }

    @Test
    void 보류가_확정_거절되어_결제에_실패하면_주문_항목_목록으로_결제_실패_이벤트가_한_번_나간다() {
        billing.reset();
        billing.wallet(BUYER_ID, 0L, 0L);
        Lecture lecture = saveOnSale("OBS 방송 세팅");

        assertThatThrownBy(() -> order(lecture)).isInstanceOf(LectureException.class);

        Long orderId = jdbcTemplate.queryForObject("select id from lectures.lecture_order", Long.class);
        Long itemId = jdbcTemplate.queryForObject("select id from lectures.order_item", Long.class);
        assertThat(events.stream(LectureOrderHoldFailedEvent.class)).singleElement().satisfies(failed -> {
            assertThat(failed.orderId()).isEqualTo(orderId);
            assertThat(failed.buyerUserId()).isEqualTo(BUYER_ID);
            assertThat(failed.items()).containsExactly(
                    new LectureOrderItemDto(itemId, lecture.getId(), "OBS 방송 세팅", PRICE_HP));
            assertThat(failed.failedAt()).isNotNull();
        });
        assertThat(events.stream(LectureOrderPaidEvent.class)).isEmpty();
    }

    @Test
    void 구매_취소_반환이_끝나면_반환_금액과_취소_시각으로_구매_취소_이벤트가_나간다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long itemId = order.items().getFirst().orderItemId();

        OrderItemResponse canceled = lectureOrderFacade.cancel(BUYER_ID, order.orderId(), itemId);

        assertThat(events.stream(LectureOrderItemCanceledEvent.class)).containsExactly(
                new LectureOrderItemCanceledEvent(itemId, BUYER_ID, "OBS 방송 세팅", PRICE_HP,
                        kst(canceled.canceledAt())));
    }

    @Test
    void 직접_확정_재생_동의_자동_확정은_각자의_확정_방식으로_확정_이벤트를_한_번씩_낸다() {
        OrderResponse order = order(saveOnSale("직접 확정"), saveOnSale("재생 동의"), saveOnSale("자동 확정"));
        Long directId = order.items().get(0).orderItemId();
        Long playId = order.items().get(1).orderItemId();
        Long autoId = order.items().get(2).orderItemId();

        OrderItemResponse direct = lectureOrderFacade.confirm(BUYER_ID, order.orderId(), directId);
        OrderItemResponse played = lectureOrderFacade.consentToPlay(BUYER_ID, order.orderId(), playId);
        // 이미 확정된 항목의 재생 동의는 아무것도 바꾸지 않는다 (D38)
        lectureOrderFacade.consentToPlay(BUYER_ID, order.orderId(), playId);
        LocalDateTime autoConfirmAt = passAutoConfirmAt(autoId);
        orderAutoConfirmUseCase.confirmDueItems();
        orderAutoConfirmUseCase.confirmDueItems();

        assertThat(events.stream(LectureOrderItemConfirmedEvent.class)).containsExactly(
                new LectureOrderItemConfirmedEvent(directId, BUYER_ID, "직접 확정", LectureConfirmationType.DIRECT,
                        kst(direct.confirmedAt())),
                new LectureOrderItemConfirmedEvent(playId, BUYER_ID, "재생 동의", LectureConfirmationType.FIRST_PLAY,
                        kst(played.confirmedAt())),
                new LectureOrderItemConfirmedEvent(autoId, BUYER_ID, "자동 확정", LectureConfirmationType.AUTO,
                        kst(autoConfirmAt)));
    }

    @Test
    void 자동_구매_확정_시각이_지난_뒤_직접_확정하면_AUTO와_자동_구매_확정_시각으로_나간다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long itemId = order.items().getFirst().orderItemId();
        LocalDateTime autoConfirmAt = passAutoConfirmAt(itemId);

        lectureOrderFacade.confirm(BUYER_ID, order.orderId(), itemId);

        assertThat(events.stream(LectureOrderItemConfirmedEvent.class)).containsExactly(
                new LectureOrderItemConfirmedEvent(itemId, BUYER_ID, "OBS 방송 세팅", LectureConfirmationType.AUTO,
                        kst(autoConfirmAt)));
    }

    @Test
    void 이의_제기를_접수하면_접수_이벤트가_나가고_같은_접수_명령을_다시_보내도_한_번만_나간다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long itemId = order.items().getFirst().orderItemId();
        LectureObjectionFileRequest request = LectureObjectionFileRequest.of(INQUIRY_ID, itemId, BUYER_ID, REASON);

        lectureObjectionFacade.file(request);
        lectureObjectionFacade.file(request);

        LocalDateTime filedAt = jdbcTemplate.queryForObject("select objection_filed_at from lectures.order_item "
                + "where id = ?", LocalDateTime.class, itemId);
        assertThat(events.stream(LectureObjectionFiledEvent.class)).containsExactly(new LectureObjectionFiledEvent(
                INQUIRY_ID, itemId, BUYER_ID, SELLER_ID, "OBS 방송 세팅", kst(filedAt)));
    }

    @Test
    void 거절된_이의_제기_접수는_접수_이벤트를_내지_않는다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long itemId = order.items().getFirst().orderItemId();

        assertThatThrownBy(() -> lectureObjectionFacade.file(LectureObjectionFileRequest.of(INQUIRY_ID, itemId,
                SELLER_ID, REASON)))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.OBJECTION_NOT_BUYER);

        assertThat(events.stream(LectureObjectionFiledEvent.class)).isEmpty();
    }

    private Long register() {
        return lectureFacade.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, List.of(
                new LectureContentSpec(uploaded(FileType.LECTURE_VIDEO), "1강 장비 고르기", ContentType.VIDEO)));
    }

    private Long uploaded(FileType fileType) {
        return fileResourceRepository.save(uploadedFile(SELLER_ID, fileType)).getId();
    }

    private Lecture saveOnSale(String title) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    private OrderResponse order(Lecture... lectures) {
        return lectureOrderFacade.createOrder(BUYER_ID, List.of(lectures).stream().map(Lecture::getId).toList());
    }

    /** 자동 구매 확정 시각을 지난 시각으로 옮긴다. 옮긴 시각을 돌려준다 */
    private LocalDateTime passAutoConfirmAt(Long orderItemId) {
        jdbcTemplate.update("update lectures.order_item set auto_confirm_at = date_trunc('second', localtimestamp) "
                + "- interval '1 hour' where id = ?", orderItemId);
        return jdbcTemplate.queryForObject("select auto_confirm_at from lectures.order_item where id = ?",
                LocalDateTime.class, orderItemId);
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time.atZone(LectureConfig.ZONE).toOffsetDateTime();
    }
}
