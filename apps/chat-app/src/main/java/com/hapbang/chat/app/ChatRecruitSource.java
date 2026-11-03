package com.hapbang.chat.app;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 모집용 단체방이 쓰는 출연 모집글·지원 이력. 원본은 출연 앱이고 chat은 복제하지 않는다(설계 3.4, Q35). 생성·초대 후보 검색·초대 때
 * 출연 내부 API를 동기로 부른다.
 * <ul>
 *     <li>호출 중에 chat DB 트랜잭션을 열어 두지 않는다. Facade가 먼저 부르고 결과를 저장 트랜잭션에 넘긴다.</li>
 *     <li>실패는 {@link ChatRecruitSourceException}이다. 응답을 받지 못했으면 지원 여부를 추정하지 않고 요청을 거절한다.</li>
 * </ul>
 */
public interface ChatRecruitSource {

    /** 지원자 목록 한 페이지의 최대 크기 (출연 계약 A2) */
    int MAX_APPLICANT_PAGE_SIZE = 500;

    /** 지원 이력 확인 한 번에 물을 수 있는 최대 회원 수 (출연 계약 A3) */
    int MAX_APPLIED_CHECK_SIZE = 100;

    /**
     * 모집글(출연 계약 A1).
     *
     * @throws ChatRecruitSourceException 모집글이 없거나({@code NOT_FOUND}) 결과를 받지 못했을 때
     */
    ChatRecruit getRecruit(Long recruitId);

    /**
     * 지원 이력이 있는 회원 ID 한 페이지(출연 계약 A2). 지원 상태와 관계없이 회원 ID 오름차순이다.
     *
     * @param afterUserId 이 회원 ID 뒤부터. null이면 처음부터
     * @param size        1~{@link #MAX_APPLICANT_PAGE_SIZE}
     */
    ChatApplicantPage getApplicants(Long recruitId, Long afterUserId, int size);

    /**
     * 주어진 회원 중 그 모집글에 지원 이력(상태 무관)이 있는 회원(출연 계약 A3).
     *
     * @param userIds 1~{@link #MAX_APPLIED_CHECK_SIZE}명
     */
    Set<Long> findApplied(Long recruitId, Collection<Long> userIds);

    /**
     * @param hostId 모집글 호스트 회원 ID
     * @param status 출연의 모집글 상태 이름 그대로({@code HOLD_PENDING}·{@code HOLD_FAILED}·{@code RECRUITING}·{@code CLOSED}·
     *               {@code CANCELED})
     */
    record ChatRecruit(Long recruitId, Long hostId, String title, String status) {

        /** 새 단체방을 만들 수 있는 모집 중 상태인지. HP 보류 처리 중·실패·마감·취소는 아니다 */
        public boolean isRecruiting() {
            return "RECRUITING".equals(status);
        }
    }

    /**
     * @param nextAfterUserId 다음 페이지를 읽을 때 넘길 값. 마지막 페이지면 null
     */
    record ChatApplicantPage(List<Long> userIds, Long nextAfterUserId) {
    }
}
