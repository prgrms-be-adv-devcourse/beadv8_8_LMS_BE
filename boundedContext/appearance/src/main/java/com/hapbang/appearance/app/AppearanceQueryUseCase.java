package com.hapbang.appearance.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.AppearanceCancelRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceResDto;
import com.hapbang.appearance.app.dto.AppearanceSummaryResDto;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceCancelRequest;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRole;
import com.hapbang.appearance.domain.AppearanceStatus;

import lombok.RequiredArgsConstructor;

/**
 * 출연 조회 (내 출연 목록, 상세). 트랜잭션은 호출한 {@link AppearanceFacade}가 연다 (읽기 전용).
 * 출연은 호스트·게스트 두 사람만 볼 수 있다. 제목은 출처(요청서·모집글)에서 가져온다.
 * 상세의 반환 진행 상태는 이 출연 단위 반환(합의 취소)만 본다 (요청서·모집글 상세의 반환과 섞지 않는다).
 */
@Service
@RequiredArgsConstructor
public class AppearanceQueryUseCase {

    private final AppearanceSupport appearanceSupport;
    private final AppearanceCancelRequestSupport appearanceCancelRequestSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceTitleSupport appearanceTitleSupport;
    private final HpTaskSupport hpTaskSupport;

    /**
     * 내 출연 목록. 최신순.
     *
     * @param role     호스트로 참여한 출연인지 게스트로 참여한 출연인지
     * @param statuses 상태 필터. 비어 있으면 전체
     */
    public Page<AppearanceSummaryResDto> getMine(Long userId, AppearanceRole role, List<AppearanceStatus> statuses,
                                                 Pageable pageable) {
        appearanceUserSupport.getParticipant(userId);
        List<AppearanceStatus> filter = orAll(statuses);
        Page<Appearance> appearances;
        if (role == AppearanceRole.HOST) {
            appearances = appearanceSupport.findByHost(userId, filter, pageable);
        } else {
            appearances = appearanceSupport.findByGuest(userId, filter, pageable);
        }
        Map<Long, String> titles = appearanceTitleSupport.titlesOf(appearances.getContent());
        return appearances.map(appearance -> AppearanceSummaryResDto.from(appearance, titles.get(appearance.getId())));
    }

    /**
     * 출연 상세. 취소 요청 이력(순번 순)을 함께 담는다.
     *
     * @throws AppearanceException 없거나 호스트·게스트가 아님({@code APPEARANCE_NOT_FOUND})
     */
    public AppearanceResDto getDetail(Long userId, Long appearanceId) {
        appearanceUserSupport.getParticipant(userId);
        Appearance appearance = appearanceSupport.getById(appearanceId);
        appearance.validateViewableBy(userId);
        List<AppearanceCancelRequestResDto> cancelRequests = new ArrayList<>();
        for (AppearanceCancelRequest cancelRequest : appearanceCancelRequestSupport.findByAppearance(appearanceId)) {
            cancelRequests.add(AppearanceCancelRequestResDto.from(cancelRequest));
        }
        return AppearanceResDto.from(appearance, appearanceTitleSupport.titleOf(appearance), cancelRequests,
                hpTaskSupport.findReleaseStatusOfAppearance(appearance),
                hpTaskSupport.findPenaltyStatusOfAppearance(appearance));
    }

    private static List<AppearanceStatus> orAll(List<AppearanceStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of(AppearanceStatus.values());
        }
        return statuses;
    }
}
