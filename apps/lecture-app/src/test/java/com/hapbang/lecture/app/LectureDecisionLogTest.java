package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.fixture.ObjectionCommands;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.billing.RealBillingTestFixture;

/**
 * 돈 흐름이나 판매 상태를 바꾼 결정이 결정한 회원 ID와 함께 INFO 한 줄을 남기는지 확인한다 (#681, docs/dev/logging.md 레벨).
 * 상태 변경 자체는 각 Facade·도메인 테스트가 맡는다.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
// 주문은 회원, 검수는 판매자·관리자, 이의 제기 명령은 내부 API 진입점이다
@WithMockUser(roles = {"USER", "VERIFIED_INFLUENCER", "ADMIN", "INTERNAL"})
class LectureDecisionLogTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long INQUIRY_ID = 500L;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureFacade lectureFacade;

    @Autowired
    private LectureInspectionFacade lectureInspectionFacade;

    @Autowired
    private LectureObjectionFacade lectureObjectionFacade;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private RealBillingTestFixture billing;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.wallet(BUYER_ID, 1_000_000L, 0L);
        billing.wallet(SELLER_ID, 0L, 0L);
    }

    @Test
    void 주문을_만들면_구매자와_건수_금액을_INFO로_남긴다(CapturedOutput output) {
        OrderResponse order = order();

        assertThat(lines(output, "주문을 만들었다")).singleElement().satisfies(line -> assertThat(line).contains(
                " INFO ", "orderId=" + order.orderId(), "buyerUserId=" + BUYER_ID, "itemCount=1",
                "totalHp=" + PRICE_HP));
    }

    @Test
    void 구매_취소를_요청하면_INFO를_남긴다(CapturedOutput output) {
        OrderResponse order = order();
        Long orderItemId = order.items().getFirst().orderItemId();

        lectureOrderFacade.cancel(BUYER_ID, order.orderId(), orderItemId);

        assertThat(lines(output, "구매 취소를 요청했다")).singleElement().satisfies(line -> assertThat(line)
                .contains(" INFO ", "orderItemId=" + orderItemId, "buyerUserId=" + BUYER_ID));
    }

    @Test
    void 구매를_확정하면_INFO를_남긴다(CapturedOutput output) {
        OrderResponse order = order();
        Long orderItemId = order.items().getFirst().orderItemId();

        lectureOrderFacade.confirm(BUYER_ID, order.orderId(), orderItemId);

        assertThat(lines(output, "구매를 확정했다")).singleElement().satisfies(line -> assertThat(line)
                .contains(" INFO ", "orderItemId=" + orderItemId, "buyerUserId=" + BUYER_ID));
    }

    @Test
    void 재생_동의는_처음_구매를_확정할_때만_INFO를_남긴다(CapturedOutput output) {
        OrderResponse order = order();
        Long orderItemId = order.items().getFirst().orderItemId();

        lectureOrderFacade.consentToPlay(BUYER_ID, order.orderId(), orderItemId);
        lectureOrderFacade.consentToPlay(BUYER_ID, order.orderId(), orderItemId);

        assertThat(lines(output, "재생 동의로 구매를 확정했다")).singleElement().satisfies(line -> assertThat(line)
                .contains(" INFO ", "orderItemId=" + orderItemId, "confirmationType=FIRST_PLAY"));
    }

    @Test
    void 이의_제기_판단과_철회는_결정한_회원과_함께_INFO를_남긴다(CapturedOutput output) {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        Long resolvedItemId = order.items().get(0).orderItemId();
        Long withdrawnItemId = order.items().get(1).orderItemId();
        ObjectionCommands objections = new ObjectionCommands(lectureObjectionFacade);
        objections.file(resolvedItemId, BUYER_ID, INQUIRY_ID, "강의 소개와 영상 내용이 다릅니다.");
        objections.file(withdrawnItemId, BUYER_ID, INQUIRY_ID + 1, "강의 소개와 영상 내용이 다릅니다.");

        objections.resolve(resolvedItemId, INQUIRY_ID, true, ADMIN_ID);
        objections.withdraw(withdrawnItemId, BUYER_ID, INQUIRY_ID + 1);

        assertThat(lines(output, "이의 제기를 판단했다")).singleElement().satisfies(line -> assertThat(line).contains(
                " INFO ", "orderItemId=" + resolvedItemId, "accepted=true", "adminUserId=" + ADMIN_ID));
        assertThat(lines(output, "이의 제기를 철회했다")).singleElement().satisfies(line -> assertThat(line)
                .contains(" INFO ", "orderItemId=" + withdrawnItemId, "buyerUserId=" + BUYER_ID));
    }

    @Test
    void 강의_검수_요청_승인_반려와_삭제는_결정한_회원과_함께_INFO를_남긴다(CapturedOutput output) {
        Long lectureId = lectureRepository.save(rejectedLecture()).getId();

        lectureFacade.requestInspection(SELLER_ID, lectureId);
        lectureInspectionFacade.reject(ADMIN_ID, lectureId, "영상 음질이 너무 낮습니다.");
        lectureFacade.requestInspection(SELLER_ID, lectureId);
        lectureInspectionFacade.approve(ADMIN_ID, lectureId);
        lectureFacade.delete(SELLER_ID, lectureId);
        lectureFacade.delete(SELLER_ID, lectureId);

        assertThat(lines(output, "강의 검수를 요청했다")).hasSize(2).allSatisfy(line -> assertThat(line)
                .contains(" INFO ", "lectureId=" + lectureId, "sellerUserId=" + SELLER_ID));
        assertThat(lines(output, "강의 검수를 반려했다")).singleElement().satisfies(line -> assertThat(line)
                .contains(" INFO ", "adminUserId=" + ADMIN_ID).doesNotContain("음질"));
        assertThat(lines(output, "강의 검수를 승인했다")).singleElement()
                .satisfies(line -> assertThat(line).contains(" INFO ", "adminUserId=" + ADMIN_ID));
        // 이미 삭제된 강의를 다시 삭제하면 아무것도 바뀌지 않아 남기지 않는다
        assertThat(lines(output, "강의를 삭제했다")).singleElement()
                .satisfies(line -> assertThat(line).contains(" INFO ", "lectureId=" + lectureId));
    }

    @Test
    void 결정한_트랜잭션이_롤백되면_결정_로그를_남기지_않는다(CapturedOutput output) {
        Long lectureId = lectureRepository.save(rejectedLecture()).getId();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            lectureFacade.requestInspection(SELLER_ID, lectureId);
            status.setRollbackOnly();
        });

        assertThat(lines(output, "강의 검수를 요청했다")).isEmpty();
    }

    private OrderResponse order(Lecture... lectures) {
        List<Lecture> ordered = lectures.length == 0 ? List.of(saveOnSale("OBS 방송 세팅")) : List.of(lectures);
        return lectureOrderFacade.createOrder(BUYER_ID, ordered.stream().map(Lecture::getId).toList());
    }

    private Lecture saveOnSale(String title) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    private static List<String> lines(CapturedOutput output, String text) {
        return output.getAll().lines().filter(line -> line.contains(text)).toList();
    }
}
