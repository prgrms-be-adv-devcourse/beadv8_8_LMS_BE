package com.hapbang.customersupport.app;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.customersupport.app.dto.InquiryResponse;
import com.hapbang.customersupport.app.dto.InquirySort;
import com.hapbang.customersupport.app.dto.InquirySummaryResponse;
import com.hapbang.customersupport.app.exception.CustomerSupportErrorCode;
import com.hapbang.customersupport.app.exception.CustomerSupportException;
import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.InquiryStatus;
import com.hapbang.testsupport.IntegrationTest;

@IntegrationTest
class InquiryUseCaseTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    @Autowired
    private InquiryUseCase inquiryUseCase;

    @Autowired
    private InquirySupporter inquirySupporter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 문의를_접수하면_답변_대기로_저장된다() {
        Inquiry created = inquiryUseCase.create(USER_ID, InquiryCategory.SERVICE_COMPLAINT,
                "환전 계좌 변경이 저장되지 않아요", "실명 확인을 누르면 오류가 나요.");

        InquiryResponse found = inquirySupporter.getMyInquiry(created.getId(), USER_ID);

        assertThat(found.inquiryId()).isNotNull();
        assertThat(found.category()).isEqualTo(InquiryCategory.SERVICE_COMPLAINT);
        assertThat(found.title()).isEqualTo("환전 계좌 변경이 저장되지 않아요");
        assertThat(found.content()).isEqualTo("실명 확인을 누르면 오류가 나요.");
        assertThat(found.status()).isEqualTo(InquiryStatus.WAITING);
        assertThat(found.createdAt()).isNotNull();
    }

    @Test
    void 내_문의만_최신순으로_조회한다() {
        Inquiry first = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "첫 문의", "내용");
        inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "남의 문의", "내용");
        Inquiry second = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "두 번째 문의", "내용");

        Page<InquirySummaryResponse> mine = inquirySupporter.getMyInquiries(USER_ID, null, InquirySort.LATEST,
                PageRequest.of(0, 20));

        assertThat(mine.getContent()).extracting(InquirySummaryResponse::inquiryId)
                .containsExactly(second.getId(), first.getId());
        assertThat(mine.getTotalElements()).isEqualTo(2);
    }

    @Test
    void 페이지로_나눠_조회한다() {
        Long first = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "1", "내용").getId();
        Long second = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "2", "내용").getId();
        Long third = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "3", "내용").getId();

        Page<InquirySummaryResponse> firstPage = inquirySupporter.getMyInquiries(USER_ID, null, InquirySort.LATEST,
                PageRequest.of(0, 2));
        Page<InquirySummaryResponse> secondPage = inquirySupporter.getMyInquiries(USER_ID, null, InquirySort.LATEST,
                PageRequest.of(1, 2));

        assertThat(firstPage.getContent()).extracting(InquirySummaryResponse::inquiryId).containsExactly(third, second);
        assertThat(secondPage.getContent()).extracting(InquirySummaryResponse::inquiryId).containsExactly(first);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
    }

    @Test
    void 오래된순으로_조회한다() {
        Long first = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "1", "내용").getId();
        Long second = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "2", "내용").getId();

        Page<InquirySummaryResponse> mine = inquirySupporter.getMyInquiries(USER_ID, null, InquirySort.OLDEST,
                PageRequest.of(0, 20));

        assertThat(mine.getContent()).extracting(InquirySummaryResponse::inquiryId).containsExactly(first, second);
    }

    @Test
    void 다른_회원의_문의는_찾을_수_없다() {
        Inquiry others = inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "남의 문의", "내용");

        assertThatThrownBy(() -> inquirySupporter.getMyInquiry(others.getId(), USER_ID))
                .isInstanceOfSatisfying(CustomerSupportException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CustomerSupportErrorCode.INQUIRY_NOT_FOUND));
    }

    @Test
    void 없는_문의는_찾을_수_없다() {
        assertThatThrownBy(() -> inquirySupporter.getMyInquiry(999L, USER_ID))
                .isInstanceOf(CustomerSupportException.class);
    }

    @Test
    void 검색어가_제목이나_내용에_들어간_내_문의만_찾는다() {
        Long byTitle = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "환전 계좌 변경", "저장이 안 돼요").getId();
        Long byContent = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "오류 문의", "환전 신청이 실패해요").getId();
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "모집 글 수정", "가능한가요?");
        inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "환전 문의", "남의 문의");

        Page<InquirySummaryResponse> found = inquirySupporter.getMyInquiries(USER_ID, " 환전 ", InquirySort.LATEST,
                PageRequest.of(0, 20));

        assertThat(found.getContent()).extracting(InquirySummaryResponse::inquiryId)
                .containsExactly(byContent, byTitle);
        assertThat(found.getTotalElements()).isEqualTo(2);
    }

    @Test
    void 검색은_대소문자를_무시한다() {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "OAuth 로그인 오류", "내용").getId();

        Page<InquirySummaryResponse> found = inquirySupporter.getMyInquiries(USER_ID, "oauth", InquirySort.LATEST,
                PageRequest.of(0, 20));

        assertThat(found.getContent()).extracting(InquirySummaryResponse::inquiryId).containsExactly(inquiryId);
    }

    @Test
    void 검색어의_퍼센트와_밑줄은_글자_그대로_찾는다() {
        Long percent = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "수수료 10% 문의", "내용").getId();
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "수수료 100원 문의", "내용");
        Long underscore = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "a_b 오류", "내용").getId();
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "axb 오류", "내용");

        assertThat(inquirySupporter.getMyInquiries(USER_ID, "10%", InquirySort.LATEST, PageRequest.of(0, 20))
                .getContent()).extracting(InquirySummaryResponse::inquiryId).containsExactly(percent);
        assertThat(inquirySupporter.getMyInquiries(USER_ID, "a_b", InquirySort.LATEST, PageRequest.of(0, 20))
                .getContent()).extracting(InquirySummaryResponse::inquiryId).containsExactly(underscore);
    }

    @Test
    void 내_문의를_삭제하면_목록과_상세에서_사라진다() {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용").getId();

        inquiryUseCase.delete(inquiryId, USER_ID);

        assertThat(inquirySupporter.getMyInquiries(USER_ID, null, InquirySort.LATEST, PageRequest.of(0, 20)))
                .isEmpty();
        assertThatThrownBy(() -> inquirySupporter.getMyInquiry(inquiryId, USER_ID))
                .isInstanceOf(CustomerSupportException.class);
        // Soft Delete: 행은 남는다
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from customersupport.inquiry where id = ? and deleted_at is not null",
                Integer.class, inquiryId)).isEqualTo(1);
    }

    @Test
    void 다른_회원의_문의나_이미_삭제된_문의는_삭제할_수_없다() {
        Long othersId = inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "남의 문의", "내용").getId();
        Long mineId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용").getId();
        inquiryUseCase.delete(mineId, USER_ID);

        assertThatThrownBy(() -> inquiryUseCase.delete(othersId, USER_ID))
                .isInstanceOfSatisfying(CustomerSupportException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CustomerSupportErrorCode.INQUIRY_NOT_FOUND));
        assertThatThrownBy(() -> inquiryUseCase.delete(mineId, USER_ID))
                .isInstanceOfSatisfying(CustomerSupportException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CustomerSupportErrorCode.INQUIRY_NOT_FOUND));
    }

    @Test
    void 답변이_달린_문의는_삭제할_수_없다() {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용").getId();
        // 관리자 답변 기능 전이라 상태를 직접 바꾼다
        jdbcTemplate.update("update customersupport.inquiry set status = 'ANSWERED' where id = ?", inquiryId);

        assertThatThrownBy(() -> inquiryUseCase.delete(inquiryId, USER_ID))
                .isInstanceOfSatisfying(CustomerSupportException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CustomerSupportErrorCode.INQUIRY_NOT_DELETABLE));
    }

    @Test
    void 관리자_목록은_모든_회원의_문의를_상태별로_조회한다() {
        Long waiting = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "대기", "내용").getId();
        Long answered = inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "완료", "내용").getId();
        Long deleted = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "삭제", "내용").getId();
        jdbcTemplate.update("update customersupport.inquiry set status = 'ANSWERED' where id = ?", answered);
        inquiryUseCase.delete(deleted, USER_ID);

        assertThat(inquirySupporter.getInquiries(null, InquirySort.OLDEST, PageRequest.of(0, 20)).getContent())
                .extracting(InquirySummaryResponse::inquiryId).containsExactly(waiting, answered);
        assertThat(inquirySupporter.getInquiries(InquiryStatus.WAITING, InquirySort.OLDEST, PageRequest.of(0, 20))
                .getContent()).extracting(InquirySummaryResponse::inquiryId).containsExactly(waiting);
        assertThat(inquirySupporter.getInquiries(InquiryStatus.ANSWERED, InquirySort.OLDEST, PageRequest.of(0, 20))
                .getContent()).extracting(InquirySummaryResponse::inquiryId).containsExactly(answered);
    }
}
