package com.hapbang.appearance.app;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.out.AppearanceRepository;

import lombok.RequiredArgsConstructor;

/**
 * 출연 저장소 접근. 트랜잭션은 호출한 쪽이 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceSupport {

    private final AppearanceRepository appearanceRepository;

    /**
     * 저장하고 바로 DB에 반영한다. 요청서 하나에 출연 하나(유니크) 제약에 걸리면 여기서 예외가 난다.
     */
    public Appearance saveNow(Appearance appearance) {
        return appearanceRepository.saveAndFlush(appearance);
    }

    /** 출연 요청서에서 성립한 출연 ID. 아직 성립하지 않았으면 null */
    public Long findIdByRequestId(Long requestId) {
        Optional<Appearance> found = appearanceRepository.findByRequestId(requestId);
        if (found.isEmpty()) {
            return null;
        }
        return found.get().getId();
    }

    /** 모집 지원 ID별 성립한 출연 ID. 성립하지 않은 지원은 결과에 없다 */
    public Map<Long, Long> findIdsByApplicationIds(Collection<Long> applicationIds) {
        Map<Long, Long> appearanceIds = new HashMap<>();
        if (applicationIds.isEmpty()) {
            return appearanceIds;
        }
        for (Appearance appearance : appearanceRepository.findByApplicationIdIn(applicationIds)) {
            appearanceIds.put(appearance.getApplicationId(), appearance.getId());
        }
        return appearanceIds;
    }
}
