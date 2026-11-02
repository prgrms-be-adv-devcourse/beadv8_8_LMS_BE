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

    public String titleOf(Appearance appearance) {
        if (appearance.isFromRecruit()) {
            return appearanceRecruitSupport.getById(appearance.getRecruitId()).getTitle();
        }
        return appearanceRequestSupport.getById(appearance.getRequestId()).getTitle();
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
