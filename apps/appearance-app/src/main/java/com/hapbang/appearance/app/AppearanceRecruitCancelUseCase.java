package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskType;

import lombok.RequiredArgsConstructor;

/**
 * 모집 전체 취소 (호스트, #239). 모집글 {@code CANCELED}, 성립한 출연마다 호스트 일방 취소(위약금), 대기 중 지원 미선정,
 * 비어 있는 자리의 보류 반환 (AGENTS.md "출연 모집 취소", ISSUE.md APR-06·APR-32).
 * <ul>
 *     <li>모집 중·마감 후 모두, 방송 예정 일시 전까지 취소할 수 있다.</li>
 *     <li>처리 중인 게스트 취소 요청이 하나라도 있으면 확인 값이 있어야 한다. 아무것도 바꾸기 전에 모든 출연을 먼저 확인한다.</li>
 *     <li>정산은 출연마다 위약금 작업 + 빈자리 반환 1건을 이 트랜잭션에 기록하고, 커밋 뒤 각각 실행한다 (H4).
 *     일부가 실패해도 모집 취소는 확정되고, 실패한 작업만 재시도·격리된다.</li>
 *     <li>잠그는 순서는 모집글 → 출연(ID 순)이다.</li>
 * </ul>
 * 정산은 Billing 호출이라 커밋 뒤에 실행해야 해서 여기서 트랜잭션을 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceRecruitCancelUseCase {

    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceSupport appearanceSupport;
    private final AppearanceApplicationSupport appearanceApplicationSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceHostCancelRecorder appearanceHostCancelRecorder;
    private final HpTaskSupport hpTaskSupport;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @return 기록한 HP 작업 ID (출연별 위약금 정산 + 빈자리 반환). 커밋 후 실행한다
     * @throws AppearanceException 호스트 자격, 없거나 볼 수 없는 모집글({@code APPEARANCE_RECRUIT_NOT_FOUND}),
     *                             호스트가 아님({@code NOT_RECRUIT_HOST}), 모집 중·마감이 아님({@code APPEARANCE_RECRUIT_NOT_CANCELABLE}),
     *                             방송 예정 일시 지남, 처리 중인 취소 요청 확인 없음({@code CANCEL_REQUEST_CONFIRM_REQUIRED}),
     *                             사유 500자 초과, 동시 처리 충돌
     */
    @Transactional
    public List<Long> cancel(Long hostId, Long recruitId, boolean confirmPendingCancelRequest, String reason) {
        appearanceUserSupport.getParticipant(hostId);
        LocalDateTime now = LocalDateTime.now(clock);
        AppearanceRecruit recruit = appearanceRecruitSupport.getForUpdate(recruitId);
        recruit.validateVisible();
        recruit.validateCancelable(hostId, now);
        List<Appearance> appearances = appearanceSupport.findActiveByRecruitForUpdate(recruitId);
        // 바꾸기 전에 모든 출연을 확인한다 (처리 중인 취소 요청 확인 값 포함). 하나라도 안 되면 아무것도 바꾸지 않는다
        for (Appearance appearance : appearances) {
            appearance.validateHostCancelable(hostId, confirmPendingCancelRequest, now);
        }

        long vacancyFee = recruit.cancel(hostId, reason, now);
        List<Long> taskIds = new ArrayList<>();
        for (Appearance appearance : appearances) {
            taskIds.add(appearanceHostCancelRecorder.cancelAndRecordPenalty(appearance, hostId,
                    confirmPendingCancelRequest, recruit.getCancelReason(), now));
        }
        for (AppearanceApplication application : appearanceApplicationSupport.findPendingByRecruit(recruitId)) {
            application.closeUnselected();
        }
        if (vacancyFee > 0) {
            taskIds.add(recordVacancyRelease(recruit, vacancyFee, now));
        }
        appearanceRecruitSupport.flushOrConflict();
        return taskIds;
    }

    /**
     * 비어 있는 자리의 보류 반환 작업. 모집 중에 취소하면 마감이 일어나지 않으므로 마감 반환과 같은 반환 요청 ID(모집글당 하나)를 쓴다.
     */
    private Long recordVacancyRelease(AppearanceRecruit recruit, long vacancyFee, LocalDateTime now) {
        String releaseRequestId = HpTask.requestId(HpTaskRefType.APPEARANCE_RECRUIT, recruit.getId(),
                HpTaskType.RELEASE, 1);
        HpTask task = HpTask.release(HpTaskRefType.APPEARANCE_RECRUIT, recruit.getId(), recruit.getHostId(),
                vacancyFee, HpTaskReason.RECRUIT_CANCELED, releaseRequestId, now);
        return hpTaskSupport.save(task).getId();
    }
}
