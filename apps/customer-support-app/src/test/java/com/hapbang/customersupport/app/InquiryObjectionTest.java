package com.hapbang.customersupport.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.customersupport.app.dto.InquiryResponse;
import com.hapbang.customersupport.app.exception.CustomerSupportErrorCode;
import com.hapbang.customersupport.app.exception.CustomerSupportException;
import com.hapbang.customersupport.domain.HpReturnStatus;
import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.InquiryStatus;
import com.hapbang.customersupport.domain.ObjectionLinkStatus;
import com.hapbang.customersupport.domain.ObjectionResult;
import com.hapbang.customersupport.fixture.LectureObjectionFixture;
import com.hapbang.customersupport.out.InquiryRepository;
import com.hapbang.shared.customersupport.event.LectureObjectionResolutionRequestedEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionWithdrawalRequestedEvent;
import com.hapbang.shared.lecture.domain.LectureObjectionCommandType;
import com.hapbang.shared.lecture.domain.LectureObjectionFailureCode;
import com.hapbang.shared.lecture.dto.LectureObjectionFileRequest;
import com.hapbang.shared.lecture.dto.LectureObjectionFiledDto;
import com.hapbang.shared.lecture.event.LectureObjectionRequestRefusedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;
import com.hapbang.shared.lecture.out.LectureApiCallExceptions;
import com.hapbang.shared.lecture.out.LectureApiClient;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 강의 내용 다름 이의 제기의 강의 연동 (#279, D60·D71). 강의 내부 API는 {@link LectureApiClient} 대역, 강의 결과 이벤트는
 * {@link LectureObjectionResultUseCase}를 직접 불러 흉내 낸다. Kafka 발행·소비는 CustomerSupportObjectionKafkaTest가 맡는다.
 */
@IntegrationTest
@RecordApplicationEvents
class InquiryObjectionTest {

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ID = 9L;
    private static final Long ORDER_ITEM_ID = 100L;

    @MockitoBean
    private LectureApiClient lectureApiClient;

    @Autowired
    private InquiryFacade inquiryFacade;

    @Autowired
    private InquiryUseCase inquiryUseCase;

    @Autowired
    private LectureObjectionResultUseCase results;

    @Autowired
    private LectureObjectionFiler lectureObjectionFiler;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ApplicationEvents events;

    @BeforeEach
    void setUp() {
        reset(lectureApiClient);
    }

    // ---- 접수 -------------------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "USER")
    void 강의가_접수하면_접수됨이고_요청은_문의_ID의_명령과_문의_내용을_싣는다() {
        given(lectureApiClient.fileObjection(any())).willAnswer(invocation -> filed(invocation.getArgument(0)));

        InquiryResponse response = createDispute();

        assertThat(response.objectionLinkStatus()).isEqualTo(ObjectionLinkStatus.FILED);
        assertThat(response.status()).isEqualTo(InquiryStatus.WAITING);
        ArgumentCaptor<LectureObjectionFileRequest> request = ArgumentCaptor.forClass(LectureObjectionFileRequest.class);
        verify(lectureApiClient).fileObjection(request.capture());
        assertThat(request.getValue()).isEqualTo(new LectureObjectionFileRequest("FILE:" + response.inquiryId(),
                response.inquiryId(), ORDER_ITEM_ID, USER_ID, "커리큘럼과 내용이 달라요."));
        assertThat(inquiry(response.inquiryId()).getObjectionCommandId()).isEqualTo("FILE:" + response.inquiryId());
    }

    @Test
    @WithMockUser(roles = "USER")
    void 강의가_바로_거절하면_문의를_지운_뒤_사유_코드로_실패한다() {
        given(lectureApiClient.fileObjection(any()))
                .willThrow(LectureApiCallExceptions.rejected(409, "OBJECTION_PERIOD_EXPIRED"));

        assertThatThrownBy(this::createDispute)
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.OBJECTION_PERIOD_EXPIRED);

        assertThat(inquiryRepository.findAll()).singleElement().satisfies(inquiry -> {
            assertThat(inquiry.getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.FILE_REJECTED);
            assertThat(inquiry.getObjectionFailureCode()).isEqualTo(LectureObjectionFailureCode.OBJECTION_PERIOD_EXPIRED);
            assertThat(inquiry.getDeletedAt()).isNotNull();
        });
    }

    @Test
    @WithMockUser(roles = "USER")
    void 결과를_모르면_접수_처리_중으로_두고_재시도_배치가_같은_명령으로_접수한다() {
        given(lectureApiClient.fileObjection(any())).willThrow(LectureApiCallExceptions.unavailable());

        InquiryResponse response = createDispute();

        assertThat(response.objectionLinkStatus()).isEqualTo(ObjectionLinkStatus.FILING);
        // 요청 안에서 막 시도한 문의는 대기 시간(1분)이 지나야 다시 보낸다
        assertThat(lectureObjectionFiler.retryDue(LocalDateTime.now())).isZero();

        reset(lectureApiClient);
        given(lectureApiClient.fileObjection(any())).willAnswer(invocation -> filed(invocation.getArgument(0)));
        assertThat(lectureObjectionFiler.retryDue(LocalDateTime.now().plusMinutes(2))).isEqualTo(1);

        assertThat(inquiry(response.inquiryId()).getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.FILED);
        ArgumentCaptor<LectureObjectionFileRequest> request = ArgumentCaptor.forClass(LectureObjectionFileRequest.class);
        verify(lectureApiClient).fileObjection(request.capture());
        assertThat(request.getValue().commandId()).isEqualTo("FILE:" + response.inquiryId());
        // 접수가 끝난 문의는 다시 보내지 않는다
        assertThat(lectureObjectionFiler.retryDue(LocalDateTime.now().plusMinutes(4))).isZero();
        verify(lectureApiClient, times(1)).fileObjection(any());
    }

    @Test
    @WithMockUser(roles = "USER")
    void 처리_중을_본_뒤의_거절은_문의를_남기고_접수_불가_사유를_보여_준다() {
        given(lectureApiClient.fileObjection(any())).willThrow(LectureApiCallExceptions.unavailable());
        Long inquiryId = createDispute().inquiryId();
        reset(lectureApiClient);
        given(lectureApiClient.fileObjection(any()))
                .willThrow(LectureApiCallExceptions.rejected(409, "OBJECTION_ALREADY_FILED"));

        lectureObjectionFiler.retryDue(LocalDateTime.now().plusMinutes(2));

        InquiryResponse mine = inquiryFacade.getMyInquiry(inquiryId, USER_ID);
        assertThat(mine.objectionLinkStatus()).isEqualTo(ObjectionLinkStatus.FILE_REJECTED);
        assertThat(mine.objectionNotice()).isEqualTo("접수 불가 · 이미 이의 제기한 항목입니다.");
        assertThat(mine.objectionFailureCode()).isNull();
        // 강의에 접수되지 않았으므로 바로 지울 수 있다
        assertThat(inquiryFacade.delete(inquiryId, USER_ID).objectionLinkStatus())
                .isEqualTo(ObjectionLinkStatus.FILE_REJECTED);
        assertThat(inquiryRepository.findByIdAndDeletedAtIsNull(inquiryId)).isEmpty();
        assertThat(events.stream(LectureObjectionWithdrawalRequestedEvent.class)).isEmpty();
    }

    @Test
    @WithMockUser(roles = "USER")
    void 계약에_없는_거절은_설정_문제라_접수_처리_중으로_둔다() {
        given(lectureApiClient.fileObjection(any())).willThrow(LectureApiCallExceptions.rejected(401, null));

        InquiryResponse response = createDispute();

        assertThat(response.objectionLinkStatus()).isEqualTo(ObjectionLinkStatus.FILING);
        assertThat(inquiryRepository.findByIdAndDeletedAtIsNull(response.inquiryId())).isPresent();
    }

    @Test
    void 이의_제기에_주문_항목이_없거나_다른_유형에_주문_항목을_보내면_실패한다() {
        assertThatThrownBy(() -> inquiryUseCase.create(USER_ID, InquiryCategory.LECTURE_CONTENT_DISPUTE, "제목",
                "내용", null))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.INQUIRY_TARGET_REQUIRED);
        assertThatThrownBy(() -> inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", ORDER_ITEM_ID))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.INQUIRY_TARGET_NOT_ALLOWED);
    }

    // ---- 판단 -------------------------------------------------------------------------------------

    @Test
    void 판단은_처리_중으로_선점하고_판단_요청_이벤트를_기록하며_같은_요청은_다시_이벤트를_내지_않는다() {
        Long inquiryId = filedDispute();

        Inquiry pending = inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "확인 결과 반환합니다.");
        Inquiry again = inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, " 확인 결과 반환합니다. ");

        assertThat(pending.getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.RESOLUTION_PENDING);
        assertThat(again.getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.RESOLUTION_PENDING);
        // 강의가 반영하기 전에는 판단·답변을 기록하지 않는다
        assertThat(again.isObjectionResolved()).isFalse();
        assertThat(again.getReplies()).isEmpty();
        assertThat(events.stream(LectureObjectionResolutionRequestedEvent.class)).containsExactly(
                new LectureObjectionResolutionRequestedEvent("RESOLVE:" + inquiryId, inquiryId, ORDER_ITEM_ID, true,
                        ADMIN_ID));
        assertRefused(() -> inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, false, "기각합니다."),
                CustomerSupportErrorCode.OBJECTION_IN_PROGRESS);
        assertRefused(() -> inquiryUseCase.delete(inquiryId, USER_ID), CustomerSupportErrorCode.OBJECTION_IN_PROGRESS);
    }

    @Test
    void 강의가_인정을_반영하면_답변_완료와_반환_처리_중이_되고_같은_결과가_다시_와도_한_번만_기록한다() {
        Long inquiryId = filedDispute();
        inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "확인 결과 반환합니다.");
        LectureObjectionResolvedEvent resolved = new LectureObjectionResolvedEvent("RESOLVE:" + inquiryId, inquiryId,
                ORDER_ITEM_ID, true);

        results.apply(resolved);
        results.apply(resolved);

        Inquiry inquiry = inquiry(inquiryId);
        assertThat(inquiry.getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.RESOLVED);
        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(inquiry.getObjectionResult()).isEqualTo(ObjectionResult.ACCEPTED);
        assertThat(inquiry.getObjectionResolvedBy()).isEqualTo(ADMIN_ID);
        assertThat(inquiry.getHpReturnStatus()).isEqualTo(HpReturnStatus.PENDING);
        assertThat(inquiry.getReplies()).singleElement()
                .satisfies(reply -> assertThat(reply.getContent()).isEqualTo("확인 결과 반환합니다."));
        // 끝난 판단에 같은 요청이 오면 그 결과, 다른 판단이면 거절
        assertThat(inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "확인 결과 반환합니다.")
                .getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.RESOLVED);
        assertRefused(() -> inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, false, "기각합니다."),
                CustomerSupportErrorCode.OBJECTION_ALREADY_RESOLVED);
    }

    @Test
    void 기각을_반영하면_HP_반환_상태가_없다() {
        Long inquiryId = filedDispute();
        inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, false, "기각합니다.");

        results.apply(new LectureObjectionResolvedEvent("RESOLVE:" + inquiryId, inquiryId, ORDER_ITEM_ID, false));

        Inquiry inquiry = inquiry(inquiryId);
        assertThat(inquiry.getObjectionResult()).isEqualTo(ObjectionResult.REJECTED);
        assertThat(inquiry.getHpReturnStatus()).isNull();
    }

    @Test
    void HP_반환_상태는_앞으로만_바뀌고_반환_완료가_판단_결과보다_먼저_와도_되돌리지_않는다() {
        Long inquiryId = filedDispute();
        inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "확인 결과 반환합니다.");

        results.apply(new LectureObjectionReturnActionRequiredEvent(inquiryId, ORDER_ITEM_ID));
        results.apply(new LectureObjectionReturnCompletedEvent(inquiryId, ORDER_ITEM_ID));
        results.apply(new LectureObjectionResolvedEvent("RESOLVE:" + inquiryId, inquiryId, ORDER_ITEM_ID, true));
        results.apply(new LectureObjectionReturnActionRequiredEvent(inquiryId, ORDER_ITEM_ID));

        Inquiry inquiry = inquiry(inquiryId);
        assertThat(inquiry.getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.RESOLVED);
        assertThat(inquiry.getHpReturnStatus()).isEqualTo(HpReturnStatus.COMPLETED);
    }

    @Test
    void 강의가_판단을_거절하면_처리_지연으로_보이고_관리자에게만_사유와_명령_ID를_보여_준다() {
        Long inquiryId = filedDispute();
        inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "확인 결과 반환합니다.");
        // 지난 명령의 거절은 무시한다
        results.apply(new LectureObjectionRequestRefusedEvent("RESOLVE:" + inquiryId + ":OLD", inquiryId, ORDER_ITEM_ID,
                LectureObjectionCommandType.RESOLVE, LectureObjectionFailureCode.OBJECTION_NOT_FILED));
        assertThat(inquiry(inquiryId).getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.RESOLUTION_PENDING);

        results.apply(new LectureObjectionRequestRefusedEvent("RESOLVE:" + inquiryId, inquiryId, ORDER_ITEM_ID,
                LectureObjectionCommandType.RESOLVE, LectureObjectionFailureCode.OBJECTION_NOT_FILED));

        assertThat(InquiryResponse.of(inquiry(inquiryId))).satisfies(mine -> {
            assertThat(mine.objectionLinkStatus()).isEqualTo(ObjectionLinkStatus.RESOLUTION_FAILED);
            assertThat(mine.objectionNotice()).isEqualTo("처리 지연 · 확인 중");
            assertThat(mine.objectionFailureCode()).isNull();
            assertThat(mine.objectionCommandId()).isNull();
        });
        assertThat(InquiryResponse.ofAdmin(inquiry(inquiryId))).satisfies(admin -> {
            assertThat(admin.objectionFailureCode()).isEqualTo("OBJECTION_NOT_FILED");
            assertThat(admin.objectionCommandId()).isEqualTo("RESOLVE:" + inquiryId);
        });
        assertRefused(() -> inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "확인 결과 반환합니다."),
                CustomerSupportErrorCode.OBJECTION_RECOVERY_REQUIRED);
    }

    @Test
    void 이의_제기_문의가_아니거나_접수되지_않았으면_판단할_수_없다() {
        Long general = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();
        Long filing = transactionTemplate.execute(status -> {
            Inquiry inquiry = inquiryRepository.save(Inquiry.createLectureDispute(USER_ID, "제목", "내용", ORDER_ITEM_ID));
            inquiry.assignFileCommand();
            return inquiry.getId();
        });

        assertRefused(() -> inquiryUseCase.resolveObjection(general, ADMIN_ID, true, "답변"),
                CustomerSupportErrorCode.NOT_OBJECTION_INQUIRY);
        assertRefused(() -> inquiryUseCase.resolveObjection(filing, ADMIN_ID, true, "답변"),
                CustomerSupportErrorCode.OBJECTION_IN_PROGRESS);
    }

    // ---- 철회 -------------------------------------------------------------------------------------

    @Test
    void 철회는_처리_중으로_선점하고_강의가_반영하면_문의가_삭제되며_다시_보내도_끝난_것으로_본다() {
        Long inquiryId = filedDispute();

        Inquiry pending = inquiryUseCase.delete(inquiryId, USER_ID);
        inquiryUseCase.delete(inquiryId, USER_ID);

        assertThat(pending.getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.WITHDRAWAL_PENDING);
        assertThat(inquiryRepository.findByIdAndDeletedAtIsNull(inquiryId)).isPresent();
        assertThat(events.stream(LectureObjectionWithdrawalRequestedEvent.class)).containsExactly(
                new LectureObjectionWithdrawalRequestedEvent("WITHDRAW:" + inquiryId, inquiryId, ORDER_ITEM_ID, USER_ID));
        // 철회 처리 중에는 판단·답변을 받지 않는다
        assertRefused(() -> inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "답변"),
                CustomerSupportErrorCode.OBJECTION_IN_PROGRESS);
        assertRefused(() -> inquiryUseCase.reply(inquiryId, ADMIN_ID, "답변"),
                CustomerSupportErrorCode.OBJECTION_IN_PROGRESS);

        results.apply(new LectureObjectionWithdrawnEvent("WITHDRAW:" + inquiryId, inquiryId, ORDER_ITEM_ID));

        assertThat(inquiryRepository.findByIdAndDeletedAtIsNull(inquiryId)).isEmpty();
        assertThat(inquiry(inquiryId).getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.WITHDRAWN);
        assertThat(inquiryUseCase.delete(inquiryId, USER_ID).getObjectionLinkStatus())
                .isEqualTo(ObjectionLinkStatus.WITHDRAWN);
        assertThat(events.stream(LectureObjectionWithdrawalRequestedEvent.class)).hasSize(1);
    }

    @Test
    void 강의가_철회를_거절하면_철회_처리_지연으로_남는다() {
        Long inquiryId = filedDispute();
        inquiryUseCase.delete(inquiryId, USER_ID);

        results.apply(new LectureObjectionRequestRefusedEvent("WITHDRAW:" + inquiryId, inquiryId, ORDER_ITEM_ID,
                LectureObjectionCommandType.WITHDRAW, LectureObjectionFailureCode.OBJECTION_INQUIRY_MISMATCH));

        assertThat(inquiry(inquiryId).getObjectionLinkStatus()).isEqualTo(ObjectionLinkStatus.WITHDRAWAL_FAILED);
        assertRefused(() -> inquiryUseCase.delete(inquiryId, USER_ID),
                CustomerSupportErrorCode.OBJECTION_RECOVERY_REQUIRED);
    }

    @Test
    void 판단과_철회가_동시에_와도_하나만_선점한다() throws Exception {
        Long inquiryId = filedDispute();
        CyclicBarrier start = new CyclicBarrier(2);

        List<Callable<Inquiry>> requests = List.of(
                () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "확인 결과 반환합니다.");
                },
                () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return inquiryUseCase.delete(inquiryId, USER_ID);
                });
        List<Throwable> failures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (Future<Inquiry> future : executor.invokeAll(requests)) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        }

        assertThat(failures).singleElement().isInstanceOfSatisfying(CustomerSupportException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CustomerSupportErrorCode.OBJECTION_IN_PROGRESS));
        assertThat(inquiry(inquiryId).getObjectionLinkStatus())
                .isIn(ObjectionLinkStatus.RESOLUTION_PENDING, ObjectionLinkStatus.WITHDRAWAL_PENDING);
    }

    @Test
    void 일반_문의는_바로_삭제한다() {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();

        inquiryUseCase.delete(inquiryId, USER_ID);

        assertThat(inquiryRepository.findByIdAndDeletedAtIsNull(inquiryId)).isEmpty();
        assertThat(events.stream(LectureObjectionWithdrawalRequestedEvent.class)).isEmpty();
    }

    @Test
    void 맞는_이의_제기_문의가_없는_결과는_무시한다() {
        Long general = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();
        Long filed = filedDispute();

        results.apply(new LectureObjectionReturnCompletedEvent(999_999L, ORDER_ITEM_ID));
        results.apply(new LectureObjectionReturnCompletedEvent(general, ORDER_ITEM_ID));
        // 주문 항목이 다르다
        results.apply(new LectureObjectionReturnCompletedEvent(filed, ORDER_ITEM_ID + 1));

        assertThat(inquiry(filed).getHpReturnStatus()).isNull();
    }

    // ---------------------------------------------------------------------------------------------

    private InquiryResponse createDispute() {
        return inquiryFacade.create(USER_ID, InquiryCategory.LECTURE_CONTENT_DISPUTE, "강의 내용이 달라요",
                "커리큘럼과 내용이 달라요.", ORDER_ITEM_ID);
    }

    private Long filedDispute() {
        return LectureObjectionFixture.filed(transactionTemplate, inquiryRepository, USER_ID, ORDER_ITEM_ID);
    }

    /** 문의와 답변을 한 트랜잭션에서 읽는다(삭제된 문의 포함) */
    private Inquiry inquiry(Long inquiryId) {
        return transactionTemplate.execute(status -> {
            Inquiry inquiry = inquiryRepository.findById(inquiryId).orElseThrow();
            inquiry.getReplies();
            return inquiry;
        });
    }

    private static LectureObjectionFiledDto filed(LectureObjectionFileRequest request) {
        return new LectureObjectionFiledDto(request.commandId(), request.inquiryId(), request.orderItemId());
    }

    private static void assertRefused(Runnable action, CustomerSupportErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}
