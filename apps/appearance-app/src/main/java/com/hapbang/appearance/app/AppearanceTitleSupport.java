package com.hapbang.appearance.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.Appearance;

import lombok.RequiredArgsConstructor;

/**
 * 출연의 제목을 찾는다. 출연은 제목을 따로 갖지 않고, 출처(요청서·모집글)의 제목을 쓴다 (출처에서 수정하면 그대로 따라간다).
 * 트랜잭션은 호출한 쪽이 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceTitleSupport {

    private final AppearanceRequestSupport appearanceRequestSupport;
    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceSupport appearanceSupport;

    public String titleOf(Appearance appearance) {
        if (appearance.isFromRecruit()) {
            return appearanceRecruitSupport.getById(appearance.getRecruitId()).getTitle();
        }
        return appearanceRequestSupport.getById(appearance.getRequestId()).getTitle();
    }

    /**
     * 출연 ID별 제목 (배치용, #871). 출연과 출처를 묶어서 읽으므로 건수와 관계없이 조회는 세 번 이하다.
     * 성립한 출연의 출처 제목은 배치가 처리할 때(요청서는 수락 뒤, 모집글은 마감 뒤) 더 바뀌지 않으므로 미리 읽어도 된다.
     */
    public Map<Long, String> titlesByAppearanceIds(Collection<Long> appearanceIds) {
        if (appearanceIds.isEmpty()) {
            return Map.of();
        }
        return titlesOf(appearanceSupport.findAllByIds(appearanceIds));
    }

    /** 출연 ID별 제목 (목록 응답용). 출처마다 한 번씩 묶어서 조회한다 */
    public Map<Long, String> titlesOf(Collection<Appearance> appearances) {
        List<Long> requestIds = new ArrayList<>();
        List<Long> recruitIds = new ArrayList<>();
        for (Appearance appearance : appearances) {
            if (appearance.isFromRecruit()) {
                recruitIds.add(appearance.getRecruitId());
            } else {
                requestIds.add(appearance.getRequestId());
            }
        }
        Map<Long, String> requestTitles = appearanceRequestSupport.findTitles(requestIds);
        Map<Long, String> recruitTitles = appearanceRecruitSupport.findTitles(recruitIds);
        Map<Long, String> titles = new HashMap<>();
        for (Appearance appearance : appearances) {
            if (appearance.isFromRecruit()) {
                titles.put(appearance.getId(), recruitTitles.get(appearance.getRecruitId()));
            } else {
                titles.put(appearance.getId(), requestTitles.get(appearance.getRequestId()));
            }
        }
        return titles;
    }
}
