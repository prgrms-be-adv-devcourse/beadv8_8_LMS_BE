package com.hapbang.appearance.app;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.AppearanceRecruitApplicantIdsResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitAppliedUserIdsResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitInternalResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;

import lombok.RequiredArgsConstructor;

/**
 * 모집글·지원 이력 내부 조회 (#586). 채팅 앱이 모집용 단체방을 만들고 초대할 때 내부 API로 부른다.
 * 트랜잭션은 호출한 {@link AppearanceRecruitInternalFacade}가 연다 (읽기 전용).
 * <ul>
 *     <li>모집글은 상태와 관계없이 준다. 회원 API에서 호스트 외에는 숨기는 HP 보류 처리 중·실패 모집글도 상태 그대로 주고,
 *     방을 만들 수 있는지는 채팅이 판단한다.</li>
 *     <li>지원 이력은 지원 상태(지원·수락·미선정·지원 취소)와 관계없이 센다. 같은 게스트는 같은 모집글에 다시 지원할 수 없어
 *     (유니크 제약) 회원마다 한 번만 나온다.</li>
 *     <li>이름: API는 채팅 쪽 말로 회원 ID({@code userId})라 하고, 출연 안(Support·Repository)은 지원의 게스트 ID({@code guestId})라 한다.
 *     지원자의 회원 ID가 곧 그 지원의 게스트 ID다.</li>
 *     <li>회원 복제를 확인하지 않는다. 탈퇴·정지 회원도 지원 이력이 있으면 나온다 (채팅 자격은 채팅이 본다).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppearanceRecruitInternalQueryUseCase {

    static final int DEFAULT_APPLICANT_PAGE_SIZE = 100;
    static final int MAX_APPLICANT_PAGE_SIZE = 500;
    static final int MAX_CHECK_USER_COUNT = 100;

    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceApplicationSupport appearanceApplicationSupport;

    /**
     * @throws AppearanceException 모집글 ID가 1 미만({@code INVALID_REQUEST}), 없는 모집글({@code APPEARANCE_RECRUIT_NOT_FOUND})
     */
    public AppearanceRecruitInternalResDto getRecruit(Long recruitId) {
        return AppearanceRecruitInternalResDto.from(appearanceRecruitSupport.getById(validRecruitId(recruitId)));
    }

    /**
     * 지원 이력이 있는 회원 ID를 오름차순 키셋으로 한 페이지 준다. 다음 페이지가 있으면 {@code nextAfterUserId}에 이 페이지의 마지막 ID를 담는다.
     *
     * @param afterUserId 이 ID보다 큰 회원부터. 생략하거나 0이면 처음부터 (회원 내부 API의 afterId와 같음)
     * @param size        한 페이지 회원 수. 생략하면 {@value #DEFAULT_APPLICANT_PAGE_SIZE}, 1~{@value #MAX_APPLICANT_PAGE_SIZE}
     * @throws AppearanceException 모집글 ID가 1 미만, afterUserId가 음수, size가 범위 밖({@code INVALID_REQUEST}),
     *                             없는 모집글({@code APPEARANCE_RECRUIT_NOT_FOUND})
     */
    public AppearanceRecruitApplicantIdsResDto findApplicantIds(Long recruitId, Long afterUserId, Integer size) {
        Long validRecruitId = validRecruitId(recruitId);
        if (afterUserId != null && afterUserId < 0) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST, "afterUserId는 0 이상이어야 합니다.");
        }
        int pageSize = size == null ? DEFAULT_APPLICANT_PAGE_SIZE : size;
        if (pageSize < 1 || pageSize > MAX_APPLICANT_PAGE_SIZE) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST,
                    "size는 1 이상 " + MAX_APPLICANT_PAGE_SIZE + " 이하여야 합니다.");
        }
        appearanceRecruitSupport.getById(validRecruitId);

        // 한 건 더 읽어 다음 페이지가 있는지 안다
        List<Long> guestIds = appearanceApplicationSupport.findGuestIdsAfter(validRecruitId,
                afterUserId == null ? 0L : afterUserId, pageSize + 1);
        if (guestIds.size() <= pageSize) {
            return new AppearanceRecruitApplicantIdsResDto(guestIds, null);
        }
        List<Long> page = List.copyOf(guestIds.subList(0, pageSize));
        return new AppearanceRecruitApplicantIdsResDto(page, page.getLast());
    }

    /**
     * 주어진 회원 중 지원 이력이 있는 회원을 오름차순으로 준다. 같은 ID를 여러 번 주면 한 번만 센다.
     *
     * @param userIds 확인할 회원 ID 1~{@value #MAX_CHECK_USER_COUNT}개 (중복 포함 개수)
     * @throws AppearanceException 모집글 ID·회원 ID가 1 미만, 회원 ID가 없거나 너무 많음({@code INVALID_REQUEST}),
     *                             없는 모집글({@code APPEARANCE_RECRUIT_NOT_FOUND})
     */
    public AppearanceRecruitAppliedUserIdsResDto findAppliedUserIds(Long recruitId, Collection<Long> userIds) {
        Long validRecruitId = validRecruitId(recruitId);
        if (userIds == null || userIds.isEmpty() || userIds.size() > MAX_CHECK_USER_COUNT) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST,
                    "userIds는 1개 이상 " + MAX_CHECK_USER_COUNT + "개 이하여야 합니다.");
        }
        Set<Long> candidateGuestIds = new LinkedHashSet<>();
        for (Long userId : userIds) {
            if (userId == null || userId < 1) {
                throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST, "userIds는 1 이상이어야 합니다.");
            }
            candidateGuestIds.add(userId);
        }
        appearanceRecruitSupport.getById(validRecruitId);
        return new AppearanceRecruitAppliedUserIdsResDto(
                appearanceApplicationSupport.findAppliedGuestIds(validRecruitId, candidateGuestIds));
    }

    private static Long validRecruitId(Long recruitId) {
        if (recruitId == null || recruitId < 1) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST, "모집글 ID는 1 이상이어야 합니다.");
        }
        return recruitId;
    }
}
