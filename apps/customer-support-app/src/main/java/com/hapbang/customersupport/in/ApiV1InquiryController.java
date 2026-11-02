package com.hapbang.customersupport.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.customersupport.app.InquiryFacade;
import com.hapbang.customersupport.app.dto.InquiryResponse;
import com.hapbang.customersupport.app.dto.InquirySort;
import com.hapbang.customersupport.app.dto.InquirySummaryResponse;
import com.hapbang.customersupport.domain.InquiryStatus;
import com.hapbang.customersupport.domain.ObjectionLinkStatus;
import com.hapbang.customersupport.in.dto.CreateInquiryRequest;
import com.hapbang.customersupport.in.dto.InquiryReplyRequest;
import com.hapbang.customersupport.in.dto.ObjectionResolutionRequest;
import com.hapbang.customersupport.global.auth.LoginUserId;
import com.hapbang.customersupport.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 고객센터 문의 REST API. 회원 ID는 팀 공통 로그인 세션에서 {@link LoginUserId}로 받는다.
 * 성공은 {@link RsData}로, 오류는 global 공통 핸들러가 ProblemDetail로 응답한다.
 * 로그인하지 않은 요청은 global SecurityConfig에서 401로 막히고, 역할 검사는 Facade의 {@code @PreAuthorize}가 한다.
 */
@RestController
@RequestMapping("/api/v1/inquiries")
@RequiredArgsConstructor
public class ApiV1InquiryController implements ApiV1InquirySpec {

    private final InquiryFacade inquiryFacade;

    /** 이의 제기 접수 결과를 모르면(강의 접수 처리 중) 202, 그 밖에는 201이다 (D60 (1)) */
    @Override
    @PostMapping
    public ResponseEntity<RsData<InquiryResponse>> createInquiry(@LoginUserId Long userId,
                                                                 @RequestBody CreateInquiryRequest request) {
        InquiryResponse response = inquiryFacade.create(userId, request.category(), request.title(),
                request.content(), request.orderItemId());
        if (response.objectionLinkStatus() == ObjectionLinkStatus.FILING) {
            return respond(HttpStatus.ACCEPTED, "문의를 저장했습니다. 이의 제기 접수를 처리하는 중입니다.", response);
        }
        return respond(HttpStatus.CREATED, "문의를 접수했습니다.", response);
    }

    @Override
    @GetMapping
    public RsData<Page<InquirySummaryResponse>> getMyInquiries(@LoginUserId Long userId,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "sort", defaultValue = "LATEST") InquirySort sort,
            Pageable pageable) {
        return RsData.ok("내 문의 목록을 조회했습니다.",
                inquiryFacade.getMyInquiries(userId, keyword, sort, pageable));
    }

    @Override
    @GetMapping("/{inquiryId}")
    public RsData<InquiryResponse> getMyInquiry(@LoginUserId Long userId,
                                                @PathVariable("inquiryId") Long inquiryId) {
        return RsData.ok("문의를 조회했습니다.", inquiryFacade.getMyInquiry(inquiryId, userId));
    }

    /** 이의 제기 철회를 강의에 요청했으면 202(철회 처리 중), 그 밖에는 204다 (D60 (2)) */
    @Override
    @DeleteMapping("/{inquiryId}")
    public ResponseEntity<RsData<InquiryResponse>> deleteInquiry(@LoginUserId Long userId,
                                                                 @PathVariable("inquiryId") Long inquiryId) {
        InquiryResponse response = inquiryFacade.delete(inquiryId, userId);
        if (response.objectionLinkStatus() == ObjectionLinkStatus.WITHDRAWAL_PENDING) {
            return respond(HttpStatus.ACCEPTED, "이의 제기 철회를 처리하는 중입니다.", response);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 관리자 API는 SecurityConfig의 {@code /api/v1/{module}/admin/**} 제한도 함께 받는다.
     */
    @Override
    @GetMapping("/admin")
    public RsData<Page<InquirySummaryResponse>> getInquiries(
            @RequestParam(name = "status", required = false) InquiryStatus status,
            @RequestParam(name = "sort", defaultValue = "OLDEST") InquirySort sort,
            Pageable pageable) {
        return RsData.ok("문의 목록을 조회했습니다.", inquiryFacade.getInquiries(status, sort, pageable));
    }

    @Override
    @GetMapping("/admin/{inquiryId}")
    public RsData<InquiryResponse> getInquiry(@PathVariable("inquiryId") Long inquiryId) {
        return RsData.ok("문의를 조회했습니다.", inquiryFacade.getInquiry(inquiryId));
    }

    @Override
    @PostMapping("/admin/{inquiryId}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<InquiryResponse> replyInquiry(@LoginUserId Long userId,
                                                @PathVariable("inquiryId") Long inquiryId,
                                                @RequestBody InquiryReplyRequest request) {
        return RsData.created("답변을 등록했습니다.", inquiryFacade.reply(inquiryId, userId, request.content()));
    }

    @Override
    @PutMapping("/admin/{inquiryId}/replies/{replyId}")
    public RsData<InquiryResponse> editReply(@PathVariable("inquiryId") Long inquiryId,
                                             @PathVariable("replyId") Long replyId,
                                             @RequestBody InquiryReplyRequest request) {
        return RsData.ok("답변을 수정했습니다.", inquiryFacade.editReply(inquiryId, replyId, request.content()));
    }

    /** 강의 반영 전이면 202(판단 처리 중), 같은 판단이 이미 반영되었으면 200이다 (D60 (2)) */
    @Override
    @PostMapping("/admin/{inquiryId}/objection-resolution")
    public ResponseEntity<RsData<InquiryResponse>> resolveObjection(@LoginUserId Long userId,
                                                                    @PathVariable("inquiryId") Long inquiryId,
                                                                    @RequestBody ObjectionResolutionRequest request) {
        InquiryResponse response = inquiryFacade.resolveObjection(inquiryId, userId, request.accepted(),
                request.content());
        if (response.objectionLinkStatus() == ObjectionLinkStatus.RESOLUTION_PENDING) {
            return respond(HttpStatus.ACCEPTED, "이의 제기 판단을 처리하는 중입니다.", response);
        }
        return respond(HttpStatus.OK, "이의 제기를 판단했습니다.", response);
    }

    private static ResponseEntity<RsData<InquiryResponse>> respond(HttpStatus status, String message,
                                                                    InquiryResponse response) {
        return ResponseEntity.status(status).body(RsData.of(status, message, response));
    }

    @Override
    @DeleteMapping("/admin/{inquiryId}/replies/{replyId}")
    public ResponseEntity<Void> deleteReply(@PathVariable("inquiryId") Long inquiryId,
                                            @PathVariable("replyId") Long replyId) {
        inquiryFacade.deleteReply(inquiryId, replyId);
        return ResponseEntity.noContent().build();
    }
}
