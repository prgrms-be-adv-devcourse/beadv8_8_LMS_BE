package com.hapbang.customersupport.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import com.hapbang.customersupport.app.dto.InquiryResponse;
import com.hapbang.customersupport.app.dto.InquirySort;
import com.hapbang.customersupport.app.dto.InquirySummaryResponse;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.InquiryStatus;

import lombok.RequiredArgsConstructor;

/**
 * Controller가 문의 기능을 쓰는 진입점. Controller는 UseCase·Supporter를 직접 쓰지 않고 이 Facade를 통한다.
 * 역할 검사({@code @PreAuthorize})는 여기서 한다(팀 규칙). 트랜잭션은 열지 않는다.
 * 쓰기는 UseCase가 커밋한 뒤 여기서 응답 DTO로 바꾼다. 그래서 새 행의 ID·작성 시각이 응답에 담긴다.
 */
@Component
@RequiredArgsConstructor
public class InquiryFacade {

    private final InquiryUseCase inquiryUseCase;
    private final InquirySupporter inquirySupporter;

    @PreAuthorize(CustomerSupportRoles.MEMBER)
    public InquiryResponse create(Long userId, InquiryCategory category, String title, String content) {
        return InquiryResponse.of(inquiryUseCase.create(userId, category, title, content));
    }

    @PreAuthorize(CustomerSupportRoles.MEMBER)
    public Page<InquirySummaryResponse> getMyInquiries(Long userId, String keyword, InquirySort sort,
                                                       Pageable pageable) {
        return inquirySupporter.getMyInquiries(userId, keyword, sort, pageable);
    }

    @PreAuthorize(CustomerSupportRoles.MEMBER)
    public InquiryResponse getMyInquiry(Long inquiryId, Long userId) {
        return inquirySupporter.getMyInquiry(inquiryId, userId);
    }

    @PreAuthorize(CustomerSupportRoles.MEMBER)
    public void delete(Long inquiryId, Long userId) {
        inquiryUseCase.delete(inquiryId, userId);
    }

    @PreAuthorize(CustomerSupportRoles.ADMIN)
    public Page<InquirySummaryResponse> getInquiries(InquiryStatus status, InquirySort sort, Pageable pageable) {
        return inquirySupporter.getInquiries(status, sort, pageable);
    }

    @PreAuthorize(CustomerSupportRoles.ADMIN)
    public InquiryResponse getInquiry(Long inquiryId) {
        return inquirySupporter.getInquiry(inquiryId);
    }
}
