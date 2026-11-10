package com.hapbang.auxiliary.search.app;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;

/**
 * 콘텐츠 분야 코드 → 이름(#771). 회원 이벤트에는 코드만 실리므로 문서에 이름을 붙일 때 쓴다.
 * <p>
 * 원본은 회원 앱의 분야 목록 내부 API(#769)이고, 10분 통계 동기화가 채운다. 아직 못 받았거나 모르는 코드는 이름이 없는(null) 채로
 * 색인하고, 다음 동기화가 문서의 이름을 채운다. 메모리 값이라 pod마다 따로 갖는다.
 */
@Component
@ConditionalOnSearchEnabled
public class ContentCategoryNames {

    private volatile Map<String, String> names = Map.of();

    /** 코드의 이름. 모르면 null */
    public String nameOf(String code) {
        return names.get(code);
    }

    /** 지금 가진 목록 전체 */
    public Map<String, String> all() {
        return names;
    }

    /** 목록을 통째로 바꾼다 */
    public void replaceAll(Map<String, String> names) {
        this.names = Map.copyOf(names);
    }
}
