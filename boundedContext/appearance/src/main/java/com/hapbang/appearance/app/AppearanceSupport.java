package com.hapbang.appearance.app;

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
}
