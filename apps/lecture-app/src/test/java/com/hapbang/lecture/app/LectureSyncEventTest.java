package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.FileType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.fixture.ObjectionCommands;
import com.hapbang.lecture.out.FileResourceRepository;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.shared.lecture.domain.LecturePurchaseResult;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.lecture.event.LectureChangedEvent;
import com.hapbang.shared.lecture.event.LecturePurchaseChangedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.billing.RealBillingTestFixture;

/**
 * AI 추천용 강의·구매 동기화 이벤트(#712, D82)가 바뀌는 지점마다 바뀐 뒤 상태로 나가는지 Facade부터 DB까지 확인한다.
 * 각 테스트는 커밋 뒤 동기화 내부 API(같은 toSyncDto, DB에서 읽음)로 같은 ID를 다시 읽어 이벤트와 비교한다.
 * 같으면 이벤트의 버전이 커밋된 행의 버전과 같고, 시각 정밀도까지 같은 값이다.
 */
@IntegrationTest
// 강의는 인증된 인플루언서, 검수·보류 무효화는 관리자, 주문은 구매자, 이의 제기·동기화 API는 내부 진입점이다
@WithMockUser(roles = {"USER", "VERIFIED_INFLUENCER", "ADMIN", "INTERNAL"})
@RecordApplicationEvents
class LectureSyncEventTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long INQUIRY_ID = 500L;

    @Autowired
    private LectureFacade lectureFacade;

    @Autowired
    private LectureInspectionFacade lectureInspectionFacade;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureObjectionFacade lectureObjectionFacade;

    @Autowired
    private LectureHpTaskAdminFacade lectureHpTaskAdminFacade;

    @Autowired
    private LectureSyncInternalFacade lectureSyncInternalFacade;

    @Autowired
    private LectureHpTaskBatchUseCase lectureHpTaskBatchUseCase;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FileResourceRepository fileResourceRepository;

    @Autowired
    private LectureHpTaskRepository lectureHpTaskRepository;

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
    void 강의를_등록하면_version_0과_콘텐츠로_이벤트가_나간다() {
        Long lectureId = register();

        LectureSyncDto event = lastLectureEvent();
        assertThat(event).isEqualTo(lectureFromApi(lectureId));
        assertThat(event.id()).isEqualTo(lectureId);
        assertThat(event.sellerUserId()).isEqualTo(SELLER_ID);
        assertThat(event.title()).isEqualTo(TITLE);
        assertThat(event.description()).isEqualTo(DESCRIPTION);
        assertThat(event.onSale()).isFalse();
        assertThat(event.deleted()).isFalse();
        assertThat(event.contents()).extracting(LectureContentSyncDto::title, LectureContentSyncDto::sequence)
                .containsExactly(tuple("1강 장비 고르기", 1), tuple("장비 목록", 2));
        assertThat(event.version()).isZero();
    }

    @Test
    void 정보를_수정하면_바뀐_제목과_오른_version으로_나간다() {
        Long lectureId = register();

        lectureFacade.updateInfo(SELLER_ID, lectureId, "새 제목", null, PRICE_HP * 2, null);

        LectureSyncDto event = lastLectureEvent();
        assertThat(event).isEqualTo(lectureFromApi(lectureId));
        assertThat(event.title()).isEqualTo("새 제목");
        assertThat(event.description()).isNull();
        assertThat(event.version()).isEqualTo(1);
    }

    @Test
    void 반려된_강의의_콘텐츠만_바꿔도_강의_version이_오르고_새_콘텐츠로_나간다() {
        Long lectureId = register();
        lectureInspectionFacade.reject(ADMIN_ID, lectureId, "영상 음질이 너무 낮습니다.");
        long rejectedVersion = lastLectureEvent().version();

        lectureFacade.replaceContents(SELLER_ID, lectureId,
                List.of(new LectureContentSpec(uploaded(FileType.LECTURE_VIDEO), "다시 찍은 1강", ContentType.VIDEO)));

        LectureSyncDto event = lastLectureEvent();
        assertThat(event).isEqualTo(lectureFromApi(lectureId));
        assertThat(event.version()).isEqualTo(rejectedVersion + 1);
        assertThat(event.contents()).extracting(LectureContentSyncDto::title, LectureContentSyncDto::sequence)
                .containsExactly(tuple("다시 찍은 1강", 1));
    }

    @Test
    void 검수를_승인하면_onSale_true로_나간다() {
        Long lectureId = register();

        lectureInspectionFacade.approve(ADMIN_ID, lectureId);

        LectureSyncDto event = lastLectureEvent();
        assertThat(event).isEqualTo(lectureFromApi(lectureId));
        assertThat(event.onSale()).isTrue();
        assertThat(event.version()).isEqualTo(1);
    }

    @Test
    void 검수_반려와_재검수_신청도_onSale_false로_나간다() {
        Long lectureId = register();

        lectureInspectionFacade.reject(ADMIN_ID, lectureId, "영상 음질이 너무 낮습니다.");
        lectureFacade.requestInspection(SELLER_ID, lectureId);

        assertThat(events.stream(LectureChangedEvent.class).map(LectureChangedEvent::lecture))
                .extracting(LectureSyncDto::version, LectureSyncDto::onSale)
                .containsExactly(tuple(0L, false), tuple(1L, false), tuple(2L, false));
        assertThat(lastLectureEvent()).isEqualTo(lectureFromApi(lectureId));
    }

    @Test
    void 강의를_삭제하면_deleted_true_onSale_false로_나가고_이미_삭제된_강의는_다시_보내지_않는다() {
        Long lectureId = register();
        lectureInspectionFacade.approve(ADMIN_ID, lectureId);

        lectureFacade.delete(SELLER_ID, lectureId);
        lectureFacade.delete(SELLER_ID, lectureId);

        assertThat(events.stream(LectureChangedEvent.class)).hasSize(3);
        LectureSyncDto event = lastLectureEvent();
        assertThat(event).isEqualTo(lectureFromApi(lectureId));
        assertThat(event.deleted()).isTrue();
        assertThat(event.onSale()).isFalse();
    }

    @Test
    void 주문하면_결제_처리_중인_항목이_PENDING과_주문_시각으로_나간다() {
        // 지갑 회로가 열려 있으면 보류를 시도하지 않아 결제 처리 중에 남는다 (D79)
        billing.openCircuit();
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        assertThat(order.status()).isEqualTo(LectureOrderStatus.HOLD_PENDING);

        List<LecturePurchaseSyncDto> purchases = purchaseEvents();
        assertThat(purchases).hasSize(2).allSatisfy(purchase -> {
            assertThat(purchase).isEqualTo(purchaseFromApi(purchase.orderItemId()));
            assertThat(purchase.buyerUserId()).isEqualTo(BUYER_ID);
            assertThat(purchase.result()).isEqualTo(LecturePurchaseResult.PENDING);
            assertThat(purchase.orderedAt()).isNotNull();
            assertThat(purchase.purchasedAt()).isNull();
            assertThat(purchase.version()).isZero();
        });
        assertThat(purchases).extracting(LecturePurchaseSyncDto::orderItemId)
                .containsExactlyElementsOf(order.items().stream().map(item -> item.orderItemId()).toList());
    }

    @Test
    void 보류가_모두_성공하면_OWNED와_구매_시각으로_나간다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        assertThat(order.status()).isEqualTo(LectureOrderStatus.PAID);

        for (var item : order.items()) {
            assertThat(resultsOf(item.orderItemId()))
                    .containsExactly(LecturePurchaseResult.PENDING, LecturePurchaseResult.OWNED);
            LecturePurchaseSyncDto last = lastPurchaseEvent(item.orderItemId());
            assertThat(last).isEqualTo(purchaseFromApi(item.orderItemId()));
            assertThat(last.purchasedAt()).isNotNull();
            assertThat(last.version()).isEqualTo(1);
        }
    }

    @Test
    void 보류가_확정_거절되어_결제에_실패하면_FAILED로_나간다() {
        billing.reset();
        billing.wallet(BUYER_ID, 0L, 0L);

        Lecture lecture = saveOnSale("OBS 방송 세팅");

        // 결제 실패는 잔액 부족으로 응답한다 (D58 (4))
        assertThatThrownBy(() -> order(lecture)).isInstanceOf(LectureException.class);

        Long itemId = purchaseEvents().getFirst().orderItemId();
        assertThat(resultsOf(itemId)).containsExactly(LecturePurchaseResult.PENDING, LecturePurchaseResult.FAILED);
        assertThat(lastPurchaseEvent(itemId)).isEqualTo(purchaseFromApi(itemId));
        assertThat(lastPurchaseEvent(itemId).purchasedAt()).isNull();
    }

    @Test
    void 보류_무효화로_결제에_실패해도_FAILED로_나간다() {
        // 같은 참조 대상에 다른 금액의 실제 보류가 먼저 생기면 멱등 키 충돌로 DEAD가 된다.
        billing.duringNextCall(() -> {
            Long conflictingItemId = jdbcTemplate.queryForObject("select order_item_id from lectures.lecture_hp_task "
                    + "where purpose = 'ORDER_HOLD'", Long.class);
            assertThat(billing.client().hold(new HoldCommand(BUYER_ID, BillingReferenceType.LECTURE_ORDER_ITEM,
                    conflictingItemId, PRICE_HP + 1L))).isInstanceOf(BillingCallResult.Succeeded.class);
        });
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long itemId = order.items().getFirst().orderItemId();
        LectureHpTask dead = lectureHpTaskRepository.findByOrderIdIn(List.of(order.orderId())).stream()
                .filter(task -> task.getPurpose() == LectureHpTaskPurpose.ORDER_HOLD
                        && task.getStatus() == LectureHpTaskStatus.DEAD)
                .findFirst().orElseThrow();

        lectureHpTaskAdminFacade.voidHold(ADMIN_ID, dead.getId());
        runDueTasks();

        assertThat(lectureOrderFacade.getOrder(BUYER_ID, order.orderId()).status())
                .isEqualTo(LectureOrderStatus.HOLD_FAILED);
        assertThat(resultsOf(itemId)).containsExactly(LecturePurchaseResult.PENDING, LecturePurchaseResult.FAILED);
        assertThat(lastPurchaseEvent(itemId)).isEqualTo(purchaseFromApi(itemId));
    }

    @Test
    void 구매_취소를_요청하면_PENDING으로_반환이_끝나면_CANCELED로_나간다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long itemId = order.items().getFirst().orderItemId();

        lectureOrderFacade.cancel(BUYER_ID, order.orderId(), itemId);

        assertThat(resultsOf(itemId)).containsExactly(LecturePurchaseResult.PENDING, LecturePurchaseResult.OWNED,
                LecturePurchaseResult.PENDING, LecturePurchaseResult.CANCELED);
        assertThat(lastPurchaseEvent(itemId)).isEqualTo(purchaseFromApi(itemId));
    }

    @Test
    void 이의_제기가_인정되면_PENDING으로_반환이_끝나면_RETURNED로_나가고_접수와_기각은_보내지_않는다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        Long acceptedId = order.items().getFirst().orderItemId();
        Long rejectedId = order.items().getLast().orderItemId();
        ObjectionCommands objections = new ObjectionCommands(lectureObjectionFacade);
        objections.file(acceptedId, BUYER_ID, INQUIRY_ID, "강의 소개와 영상 내용이 다릅니다.");
        objections.file(rejectedId, BUYER_ID, INQUIRY_ID + 1, "강의 소개와 영상 내용이 다릅니다.");

        objections.resolve(acceptedId, INQUIRY_ID, true, ADMIN_ID);
        objections.resolve(rejectedId, INQUIRY_ID + 1, false, ADMIN_ID);

        assertThat(resultsOf(acceptedId)).containsExactly(LecturePurchaseResult.PENDING,
                LecturePurchaseResult.OWNED, LecturePurchaseResult.PENDING);
        assertThat(lastPurchaseEvent(acceptedId)).isEqualTo(purchaseFromApi(acceptedId));
        assertThat(resultsOf(rejectedId)).containsExactly(LecturePurchaseResult.PENDING, LecturePurchaseResult.OWNED);

        lectureHpTaskBatchUseCase.runDue();

        assertThat(resultsOf(acceptedId)).endsWith(LecturePurchaseResult.RETURNED);
        assertThat(lastPurchaseEvent(acceptedId)).isEqualTo(purchaseFromApi(acceptedId));
    }

    private Long register() {
        List<LectureContentSpec> specs = List.of(
                new LectureContentSpec(uploaded(FileType.LECTURE_VIDEO), "1강 장비 고르기", ContentType.VIDEO),
                new LectureContentSpec(uploaded(FileType.LECTURE_DOCUMENT), "장비 목록", ContentType.DOCUMENT));
        return lectureFacade.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, specs);
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

    private void runDueTasks() {
        jdbcTemplate.update("update lectures.lecture_hp_task set next_attempt_at = now() - interval '10 minutes', "
                + "lease_until = case when lease_until is null then null else now() - interval '10 minutes' end "
                + "where status in ('PENDING', 'PROCESSING')");
        lectureHpTaskBatchUseCase.runDue();
    }

    private LectureSyncDto lastLectureEvent() {
        return events.stream(LectureChangedEvent.class).map(LectureChangedEvent::lecture)
                .reduce((first, second) -> second).orElseThrow();
    }

    private List<LecturePurchaseSyncDto> purchaseEvents() {
        return events.stream(LecturePurchaseChangedEvent.class).map(LecturePurchaseChangedEvent::purchase).toList();
    }

    private List<LecturePurchaseResult> resultsOf(Long orderItemId) {
        return purchaseEvents().stream().filter(purchase -> purchase.orderItemId().equals(orderItemId))
                .map(LecturePurchaseSyncDto::result).toList();
    }

    private LecturePurchaseSyncDto lastPurchaseEvent(Long orderItemId) {
        return purchaseEvents().stream().filter(purchase -> purchase.orderItemId().equals(orderItemId))
                .reduce((first, second) -> second).orElseThrow();
    }

    /** 커밋된 행을 동기화 내부 API와 같은 방법으로 읽는다 */
    private LectureSyncDto lectureFromApi(Long lectureId) {
        return lectureSyncInternalFacade.getLectures(lectureId - 1, 1).items().getFirst();
    }

    private LecturePurchaseSyncDto purchaseFromApi(Long orderItemId) {
        return lectureSyncInternalFacade.getPurchases(orderItemId - 1, 1).items().getFirst();
    }
}
