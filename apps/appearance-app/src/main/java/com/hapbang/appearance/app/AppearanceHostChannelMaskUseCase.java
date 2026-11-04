package com.hapbang.appearance.app;

import java.util.Set;

import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.AppearanceBasicInfo;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.out.AppearanceRecruitRepository;
import com.hapbang.appearance.out.AppearanceRequestRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 탈퇴 회원 개인정보 파기(#675, 회원 #594). 회원 앱이 복구 기한이 지난 탈퇴 회원을 파기하고 보낸 {@code UserMaskedEvent}로 불린다.
 * 트랜잭션은 호출한 {@link AppearanceUserFacade}가 연다.
 * <ul>
 *     <li>파기 대상은 그 회원이 호스트로 직접 입력한 요청서·모집글의 호스트 채널 URL이다(docs/policy.md 삭제 정책·파기 방법).
 *     {@link AppearanceBasicInfo#WITHDRAWN_HOST_CHANNEL_URL}로 덮는다. 다른 출연 기록(출연·지원·리뷰·HP 작업)에는 채널 주소가 없다.</li>
 *     <li>이미 덮은 행은 건드리지 않으므로 같은 이벤트를 여러 번 받아도 결과가 같다(최소 한 번 전달).</li>
 *     <li>진행 중인 요청서·모집글은 덮지 않고 error 로그를 남긴다. 탈퇴 확인(#624)이 막았어야 하는 비정상 상태라
 *     그 거래가 끝난 뒤 운영이 확인한다. 끝난 행은 그대로 덮는다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceHostChannelMaskUseCase {

    /** 진행 중인 요청서: 보류 처리 중이거나 게스트 응답 대기 */
    static final Set<AppearanceRequestStatus> ACTIVE_REQUEST_STATUSES =
            Set.of(AppearanceRequestStatus.HOLD_PENDING, AppearanceRequestStatus.REQUESTED);
    /** 진행 중인 모집글: 보류 처리 중이거나 모집 중 */
    static final Set<AppearanceRecruitStatus> ACTIVE_RECRUIT_STATUSES =
            Set.of(AppearanceRecruitStatus.HOLD_PENDING, AppearanceRecruitStatus.RECRUITING);

    private final AppearanceRequestRepository appearanceRequestRepository;
    private final AppearanceRecruitRepository appearanceRecruitRepository;

    public void mask(Long userId) {
        int requests = appearanceRequestRepository.maskHostChannelUrl(userId,
                AppearanceBasicInfo.WITHDRAWN_HOST_CHANNEL_URL, ACTIVE_REQUEST_STATUSES);
        int recruits = appearanceRecruitRepository.maskHostChannelUrl(userId,
                AppearanceBasicInfo.WITHDRAWN_HOST_CHANNEL_URL, ACTIVE_RECRUIT_STATUSES);
        log.info("탈퇴 회원 파기: 호스트 채널 URL을 덮었다 userId={}, requests={}, recruits={}", userId, requests, recruits);

        long activeRequests = appearanceRequestRepository.countByHostIdAndStatusIn(userId, ACTIVE_REQUEST_STATUSES);
        long activeRecruits = appearanceRecruitRepository.countByHostIdAndStatusIn(userId, ACTIVE_RECRUIT_STATUSES);
        if (activeRequests > 0 || activeRecruits > 0) {
            log.error("[HOST_CHANNEL_MASK_SKIPPED] 탈퇴 회원 파기: 진행 중인 요청서·모집글이 있어 채널 URL을 덮지 않았다(탈퇴 확인에서 "
                    + "막혔어야 함, 운영 확인 필요) userId={}, activeRequests={}, activeRecruits={}",
                    userId, activeRequests, activeRecruits);
        }
    }
}
