package com.hapbang.customersupport.app;

import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.customersupport.app.dto.InquiryResponse;
import com.hapbang.customersupport.app.dto.InquirySort;
import com.hapbang.customersupport.app.dto.InquirySummaryResponse;
import com.hapbang.customersupport.app.exception.CustomerSupportErrorCode;
import com.hapbang.customersupport.app.exception.CustomerSupportException;
import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryStatus;
import com.hapbang.customersupport.out.InquiryRepository;

import lombok.RequiredArgsConstructor;

/** 문의 읽기 전용 기능. 쓰기({@link InquiryUseCase})와 나눈다 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class InquirySupporter {

    private final InquiryRepository inquiryRepository;

    /**
     * 내 문의 목록. 검색어가 있으면 제목이나 내용에 검색어가 들어간 문의만 찾는다(대소문자 무시).
     * 정렬은 {@code sort}로만 정하고 필드 이름 정렬(Pageable의 sort)은 무시한다.
     */
    public Page<InquirySummaryResponse> getMyInquiries(Long userId, String keyword, InquirySort sort,
                                                       Pageable pageable) {
        PageRequest sorted = sorted(sort, pageable);
        Page<Inquiry> inquiries = keyword == null || keyword.isBlank()
                ? inquiryRepository.findByUserIdAndDeletedAtIsNull(userId, sorted)
                : inquiryRepository.searchByUserId(userId, containsPattern(keyword), sorted);
        return inquiries.map(InquirySummaryResponse::from);
    }

    /** 내 문의 상세. 다른 회원의 문의는 있는지도 알 수 없게 404로 응답한다 */
    public InquiryResponse getMyInquiry(Long inquiryId, Long userId) {
        return inquiryRepository.findByIdAndDeletedAtIsNull(inquiryId)
                .filter(inquiry -> inquiry.isWrittenBy(userId))
                .map(InquiryResponse::of)
                .orElseThrow(() -> new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_NOT_FOUND));
    }

    /** 관리자 문의 목록. 상태가 없으면 전체. 삭제된 문의는 빠진다 */
    public Page<InquirySummaryResponse> getInquiries(InquiryStatus status, InquirySort sort, Pageable pageable) {
        PageRequest sorted = sorted(sort, pageable);
        Page<Inquiry> inquiries = status == null
                ? inquiryRepository.findByDeletedAtIsNull(sorted)
                : inquiryRepository.findByStatusAndDeletedAtIsNull(status, sorted);
        return inquiries.map(InquirySummaryResponse::from);
    }

    /** 관리자 문의 상세. 모든 회원의 문의를 본다. 삭제된 문의는 404 */
    public InquiryResponse getInquiry(Long inquiryId) {
        return inquiryRepository.findByIdAndDeletedAtIsNull(inquiryId)
                .map(InquiryResponse::of)
                .orElseThrow(() -> new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_NOT_FOUND));
    }

    private static PageRequest sorted(InquirySort sort, Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortOf(sort));
    }

    /** 검색어를 글자 그대로 찾는 LIKE 패턴. 검색어의 %, _는 와일드카드가 아니다 */
    private static String containsPattern(String keyword) {
        String escaped = keyword.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** 접수 순서는 id 순서와 같다 */
    private static Sort sortOf(InquirySort sort) {
        return switch (sort) {
            case LATEST -> Sort.by(Sort.Order.desc("id"));
            case OLDEST -> Sort.by(Sort.Order.asc("id"));
        };
    }
}
