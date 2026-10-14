package com.hapbang.customersupport.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.customersupport.app.exception.CustomerSupportErrorCode;
import com.hapbang.customersupport.app.exception.CustomerSupportException;
import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.InquiryStatus;
import com.hapbang.customersupport.domain.InquiryTargetType;
import com.hapbang.customersupport.domain.ObjectionResult;
import com.hapbang.customersupport.out.InquiryRepository;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException.Reason;
import com.hapbang.shared.lecture.port.LectureObjectionPort;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 강의 내용 다름 이의 제기 접수·판단. 강의 모듈은 Mockito 목({@code LectureObjectionPortMockConfig})으로 대신한다.
 */
@IntegrationTest
class InquiryObjectionTest {

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ID = 9L;
    private static final Long ORDER_ITEM_ID = 100L;

    @Autowired
    private InquiryUseCase inquiryUseCase;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private LectureObjectionPort lectureObjectionPort;

    @BeforeEach
    void setUp() {
        reset(lectureObjectionPort);
    }

    @Test
    void 이의_제기를_접수하면_대상과_함께_저장하고_강의_모듈에_접수한다() {
        Inquiry inquiry = fileDispute();

        Inquiry saved = inquiryRepository.findById(inquiry.getId()).orElseThrow();
        assertThat(saved.getCategory()).isEqualTo(InquiryCategory.LECTURE_CONTENT_DISPUTE);
        assertThat(saved.getTargetType()).isEqualTo(InquiryTargetType.LECTURE_ORDER_ITEM);
        assertThat(saved.getTargetId()).isEqualTo(ORDER_ITEM_ID);
        verify(lectureObjectionPort).fileObjection(ORDER_ITEM_ID, USER_ID, inquiry.getId(), "커리큘럼과 내용이 달라요.");
    }

    @ParameterizedTest
    @EnumSource(value = Reason.class, names = {"NOT_BUYER", "NOT_OBJECTIONABLE", "ALREADY_PAID_OUT", "ALREADY_FILED",
            "PERIOD_EXPIRED"})
    void 강의_모듈이_접수를_거절하면_문의를_만들지_않고_사유별_코드로_실패한다(Reason reason) {
        refuseFile(reason);

        assertThatThrownBy(this::fileDispute)
                .isInstanceOf(CustomerSupportException.class)
                .hasMessage(reason.getMessage())
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.of(reason));
        assertThat(inquiryRepository.count()).isZero();
    }

    @Test
    void 이의_제기에_주문_항목이_없으면_실패한다() {
        assertThatThrownBy(() -> inquiryUseCase.create(USER_ID, InquiryCategory.LECTURE_CONTENT_DISPUTE, "제목",
                "내용", null))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.INQUIRY_TARGET_REQUIRED);
        verifyNoInteractions(lectureObjectionPort);
    }

    @Test
    void 다른_유형에_주문_항목을_보내면_실패한다() {
        assertThatThrownBy(() -> inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", ORDER_ITEM_ID))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.INQUIRY_TARGET_NOT_ALLOWED);
    }

    @Test
    void 판단하면_결과를_저장하고_답변을_달아_답변_완료가_되며_강의_모듈에_반영한다() {
        Long inquiryId = fileDispute().getId();

        Inquiry resolved = inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "확인 결과 내용이 달라 반환합니다.");

        assertThat(resolved.getObjectionResult()).isEqualTo(ObjectionResult.ACCEPTED);
        assertThat(resolved.getObjectionResolvedBy()).isEqualTo(ADMIN_ID);
        assertThat(resolved.getObjectionResolvedAt()).isNotNull();
        assertThat(resolved.getStatus()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(resolved.getReplies()).singleElement()
                .satisfies(reply -> assertThat(reply.getContent()).isEqualTo("확인 결과 내용이 달라 반환합니다."));
        verify(lectureObjectionPort).resolveObjection(ORDER_ITEM_ID, inquiryId, true, ADMIN_ID);
    }

    @Test
    void 강의_모듈이_판단을_거절하면_판단과_답변이_저장되지_않는다() {
        Long inquiryId = fileDispute().getId();
        doThrow(new LectureObjectionRefusedException(Reason.NOT_FILED)).when(lectureObjectionPort)
                .resolveObjection(any(), any(), anyBoolean(), any());

        assertThatThrownBy(() -> inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, false, "기각합니다."))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.OBJECTION_NOT_FILED);

        Inquiry inquiry = inquiryRepository.findWithRepliesByIdAndDeletedAtIsNull(inquiryId).orElseThrow();
        assertThat(inquiry.isObjectionResolved()).isFalse();
        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.WAITING);
        assertThat(inquiry.getReplies()).isEmpty();
    }

    @Test
    void 이미_판단한_이의_제기는_다시_판단할_수_없다() {
        Long inquiryId = fileDispute().getId();
        inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, false, "기각합니다.");

        assertThatThrownBy(() -> inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "인정합니다."))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.OBJECTION_ALREADY_RESOLVED);
        verify(lectureObjectionPort, times(1)).resolveObjection(any(), any(), anyBoolean(), any());
    }

    @Test
    void 이의_제기_문의가_아니면_판단할_수_없다() {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();

        assertThatThrownBy(() -> inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "인정합니다."))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.NOT_OBJECTION_INQUIRY);
    }

    @Test
    void 이의_제기_문의는_철회_연동_전까지_삭제할_수_없다() {
        Long inquiryId = fileDispute().getId();

        assertThatThrownBy(() -> inquiryUseCase.delete(inquiryId, USER_ID))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.OBJECTION_INQUIRY_NOT_DELETABLE);
    }

    @Test
    void 판단한_이의_제기는_답변을_모두_지워도_답변_완료로_남는다() {
        Long inquiryId = fileDispute().getId();
        Long replyId = inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, false, "기각합니다.")
                .getReplies().getFirst().getId();

        inquiryUseCase.deleteReply(inquiryId, replyId);

        Inquiry inquiry = inquiryRepository.findWithRepliesByIdAndDeletedAtIsNull(inquiryId).orElseThrow();
        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.ANSWERED);
    }

    @Test
    void 모든_거절_사유에_오류_코드가_정해진다() {
        assertThat(Reason.values()).allSatisfy(reason -> assertThat(CustomerSupportErrorCode.of(reason))
                .isNotEqualTo(CustomerSupportErrorCode.OBJECTION_REFUSED));
    }

    private void refuseFile(Reason reason) {
        doThrow(new LectureObjectionRefusedException(reason)).when(lectureObjectionPort)
                .fileObjection(any(), any(), any(), any());
    }

    private Inquiry fileDispute() {
        return inquiryUseCase.create(USER_ID, InquiryCategory.LECTURE_CONTENT_DISPUTE, "강의 내용이 달라요",
                "커리큘럼과 내용이 달라요.", ORDER_ITEM_ID);
    }
}
