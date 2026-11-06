package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 버전(#712)을 단 장바구니 항목·리뷰를 동시에 바꿔도 낙관적 락 충돌(500) 없이 둘 다 성공하는지 확인한다 (D82).
 * 앞 요청이 바꾼 뒤 커밋하기 전(동기화 이벤트 발행 직전)에 잠시 멈추게 해, 뒤 요청이 그사이 같은 행을 읽게 만든다.
 * 행을 잠그지 않으면 뒤 요청이 먼저 커밋하고 앞 요청이 버전 충돌로 실패한다.
 */
@IntegrationTest
// spy 빈 때문에 컨텍스트를 따로 띄운다. 남겨 두면 DB 연결을 계속 잡아 테스트 PostgreSQL의 연결 수 상한을 넘기므로 끝나면 닫는다
@DirtiesContext
class LectureSyncConcurrencyTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final long PAUSE_MILLIS = 500;
    private static final Long INQUIRY_ID = 500L;

    @MockitoSpyBean
    private LectureSyncEvents lectureSyncEvents;

    @Autowired
    private CartAddItemUseCase cartAddItemUseCase;

    @Autowired
    private CartRemoveItemUseCase cartRemoveItemUseCase;

    @Autowired
    private ReviewWriteUseCase reviewWriteUseCase;

    @Autowired
    private ReviewEditUseCase reviewEditUseCase;

    @Autowired
    private OrderObjectionResolveUseCase orderObjectionResolveUseCase;

    // 강의 동기화 목록이 강의 행을 읽은 직후에 다른 요청을 끼워 넣는다. 정하지 않으면 실제 메서드를 부른다
    @MockitoSpyBean
    private LectureRepository lectureRepository;

    @Autowired
    private LectureSyncInternalFacade lectureSyncInternalFacade;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void 장바구니_빼기를_동시에_두_번_해도_둘_다_성공하고_한_번만_빠진다() throws Exception {
        Long lectureId = saveOnSale().getId();
        inTransaction(() -> cartAddItemUseCase.addItem(BUYER_ID, lectureId));
        CountDownLatch firstPaused = new CountDownLatch(1);
        doAnswer(invocation -> pauseFirstThenPublish(firstPaused, invocation))
                .when(spy()).cartItemChanged(any());
        clearInvocations(spy());

        runConcurrently(firstPaused,
                () -> cartRemoveItemUseCase.removeItem(BUYER_ID, lectureId),
                () -> cartRemoveItemUseCase.removeItem(BUYER_ID, lectureId));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select deleted_at, version from lectures.cart_item where lecture_id = ?", lectureId);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("version")).isEqualTo(1L);
        // 뒤 요청은 앞 요청이 뺀 것을 보고 아무것도 하지 않는다
        verify(spy(), times(1)).cartItemChanged(any());
    }

    @Test
    void 리뷰를_동시에_두_번_고쳐도_둘_다_성공하고_나중_요청이_남는다() throws Exception {
        Lecture lecture = saveOnSale();
        Long itemId = confirmedItem(lecture);
        Long reviewId = inTransaction(() -> reviewWriteUseCase.write(BUYER_ID, lecture.getId(), itemId, 5, "좋아요"));
        CountDownLatch firstPaused = new CountDownLatch(1);
        doAnswer(invocation -> pauseFirstThenPublish(firstPaused, invocation))
                .when(spy()).reviewChanged(any());
        clearInvocations(spy());

        runConcurrently(firstPaused,
                () -> reviewEditUseCase.edit(BUYER_ID, reviewId, 4, "괜찮아요"),
                () -> reviewEditUseCase.edit(BUYER_ID, reviewId, 3, "보통이에요"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select rating, version from lectures.lecture_review where id = ?", reviewId);
        assertThat(row.get("rating")).isEqualTo(3);
        assertThat(row.get("version")).isEqualTo(2L);
        verify(spy(), times(2)).reviewChanged(any());
    }

    // 리뷰 작성이 counted=true로 발행한 뒤 커밋하기 전에 이의 제기가 인정되면, 인정 쪽은 아직 커밋되지 않은 리뷰를 보지 못해 version을 올리지 못한다.
    // 그러면 같은 version에 이벤트는 counted=true, 내부 API는 counted=false가 된다. 리뷰 작성도 주문 행을 잠가 둘을 한 줄로 세운다 (D82)
    @Test
    void 리뷰_작성과_이의_제기_인정이_겹쳐도_리뷰_version이_올라_counted_false가_다시_나간다() throws Exception {
        Lecture lecture = saveOnSale();
        Long itemId = confirmedItem(lecture);
        inTransaction(() -> lectureOrderRepository.findByItemId(itemId).orElseThrow()
                .fileObjection(itemId, BUYER_ID, INQUIRY_ID, "내용이 다릅니다.", LocalDateTime.now(LectureConfig.ZONE)));
        CountDownLatch firstPaused = new CountDownLatch(1);
        doAnswer(invocation -> publishThenPause(firstPaused, invocation)).when(spy()).reviewChanged(any());
        clearInvocations(spy());

        runConcurrently(firstPaused,
                () -> reviewWriteUseCase.write(BUYER_ID, lecture.getId(), itemId, 5, "좋아요"),
                () -> orderObjectionResolveUseCase.resolveObjection(itemId, INQUIRY_ID, true, ADMIN_ID));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select deleted_at, version from lectures.lecture_review where order_item_id = ?", itemId);
        assertThat(row.get("deleted_at")).isNull();
        // 작성(version 0, counted=true) 뒤 인정이 version을 올려 counted=false로 다시 보냈다
        assertThat(row.get("version")).isEqualTo(1L);
        verify(spy(), times(1)).reviewCountChanged(any());
    }

    // 강의 행과 콘텐츠(@BatchSize)는 다른 쿼리라, 사이에 콘텐츠 교체가 커밋되면 READ COMMITTED에서는 옛 version에 새 콘텐츠가 실린다.
    // REPEATABLE READ로 읽어 강의 행을 읽은 시점의 콘텐츠를 싣는다 (#712 리뷰, D82)
    @Test
    @WithMockUser(roles = "INTERNAL")
    void 강의_동기화_목록은_강의를_읽은_뒤_콘텐츠가_바뀌어도_읽은_version의_콘텐츠를_싣는다() throws Exception {
        Long lectureId = lectureRepository.save(rejectedLecture()).getId();
        // Spring Data Repository는 JDK 프록시라 spy의 기본 응답이 실제 빈에 넘기는 것(delegatesTo)이다. callRealMethod 대신 그것을 부른다
        Answer<?> real = Mockito.mockingDetails(lectureRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object rows = real.answer(invocation);
            // 다른 요청이 그사이 콘텐츠를 바꾸고 커밋한다(강의 version 0 → 1)
            executor.submit(() -> inTransaction(() -> lectureRepository.findById(lectureId).orElseThrow()
                    .replaceContents(SELLER_ID, List.of(new LectureContentSpec(201L, "다시 찍은 1강", ContentType.VIDEO)),
                            NOW.plusMinutes(1)))).get(10, TimeUnit.SECONDS);
            return rows;
        }).when(lectureRepository).findByIdGreaterThanOrderByIdAsc(any(), any());

        LectureSyncDto synced = lectureSyncInternalFacade.getLectures(lectureId - 1, 1).items().getFirst();

        assertThat(synced.version()).isZero();
        assertThat(synced.contents()).extracting(LectureContentSyncDto::title)
                .containsExactly("1강 장비 고르기", "장비 목록");
    }

    /** 첫 호출(앞 요청)이면 실제 발행을 한 뒤 커밋하기 전에 잠시 멈춘다 */
    private Object publishThenPause(CountDownLatch firstPaused, InvocationOnMock invocation) throws Throwable {
        Object result = invocation.callRealMethod();
        if (firstPaused.getCount() > 0) {
            firstPaused.countDown();
            Thread.sleep(PAUSE_MILLIS);
        }
        return result;
    }

    /**
     * 첫 호출(앞 요청)이면 잠시 멈춘 뒤 실제 발행(flush)을 한다. 멈추는 동안 앞 요청은 행을 바꿨지만 아직 DB에 반영·커밋하지 않았다.
     * 뒤 요청의 호출은 멈추지 않는다
     */
    private Object pauseFirstThenPublish(CountDownLatch firstPaused, InvocationOnMock invocation) throws Throwable {
        if (firstPaused.getCount() > 0) {
            firstPaused.countDown();
            Thread.sleep(PAUSE_MILLIS);
        }
        return invocation.callRealMethod();
    }

    /** 주입된 빈은 트랜잭션 프록시라, 스텁·검증은 그 안의 spy에 한다 (프록시로 하면 MANDATORY 트랜잭션 검사에 걸린다) */
    private LectureSyncEvents spy() {
        return AopTestUtils.getUltimateTargetObject(lectureSyncEvents);
    }

    private void runConcurrently(CountDownLatch firstPaused, Runnable first, Runnable second) throws Exception {
        Future<?> firstRequest = executor.submit(() -> inTransaction(first));
        assertThat(firstPaused.await(5, TimeUnit.SECONDS)).as("앞 요청이 발행 직전에 멈췄다").isTrue();
        Future<?> secondRequest = executor.submit(() -> inTransaction(second));
        // 실패하면 ExecutionException으로 원인(낙관적 락 충돌 등)이 드러난다
        firstRequest.get(10, TimeUnit.SECONDS);
        secondRequest.get(10, TimeUnit.SECONDS);
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private Lecture saveOnSale() {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    /** 구매 확정한 주문 항목. 리뷰를 쓸 수 있다 */
    private Long confirmedItem(Lecture lecture) {
        LocalDateTime now = LocalDateTime.now(LectureConfig.ZONE);
        LectureOrder order = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(lecture), now));
        Long itemId = order.getItems().getFirst().getId();
        inTransaction(() -> {
            lectureOrderRepository.findByItemId(itemId).orElseThrow().confirmItem(itemId, now);
        });
        return itemId;
    }
}
