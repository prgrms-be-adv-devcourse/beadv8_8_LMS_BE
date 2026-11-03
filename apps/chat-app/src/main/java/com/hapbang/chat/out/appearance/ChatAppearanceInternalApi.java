package com.hapbang.chat.out.appearance;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * 출연 내부 HTTP API의 chat-app 전용 HTTP Interface(설계 3.4 "출연 내부 API 계약 초안"). 프록시는 {@link ChatAppearanceApiConfig}가
 * 만든다(기본 주소·내부 API 키 헤더·제한 시간·오류 변환).
 * <p>
 * 응답은 출연 앱의 성공 봉투(RsData)다. chat-app은 출연 앱 코드에 의존하지 않으므로 같은 필드의 record로 읽는다.
 */
@HttpExchange(ChatAppearanceInternalApi.RECRUITS_PATH)
public interface ChatAppearanceInternalApi {

    String RECRUITS_PATH = "/api/v1/internal/appearances/recruits";

    /** A1 모집글 조회 */
    @GetExchange("/{recruitId}")
    ResponseEntity<RecruitEnvelope> getRecruit(@PathVariable("recruitId") Long recruitId);

    /** A2 지원자 회원 ID 목록(회원 ID 오름차순 키셋). afterUserId가 null이면 쿼리에서 빼고 처음부터 읽는다 */
    @GetExchange("/{recruitId}/applicants")
    ResponseEntity<ApplicantsEnvelope> getApplicants(@PathVariable("recruitId") Long recruitId,
            @RequestParam(name = "afterUserId", required = false) Long afterUserId, @RequestParam("size") int size);

    /** A3 지원 이력 확인 */
    @GetExchange("/{recruitId}/applicants/exists")
    ResponseEntity<AppliedEnvelope> findApplied(@PathVariable("recruitId") Long recruitId,
            @RequestParam("userIds") List<Long> userIds);

    /** A1 응답의 {@code data} */
    record Recruit(Long recruitId, Long hostId, String title, String status) {
    }

    /** A2 응답의 {@code data}. 마지막 페이지면 {@code nextAfterUserId}가 없다 */
    record Applicants(List<Long> userIds, Long nextAfterUserId) {
    }

    /** A3 응답의 {@code data} */
    record Applied(List<Long> appliedUserIds) {
    }

    record RecruitEnvelope(Integer statusCode, String message, Recruit data) {
    }

    record ApplicantsEnvelope(Integer statusCode, String message, Applicants data) {
    }

    record AppliedEnvelope(Integer statusCode, String message, Applied data) {
    }
}
