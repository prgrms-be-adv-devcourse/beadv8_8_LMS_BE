package com.hapbang.auxiliary.search.influencer.app;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.common.app.ZeroResultKeywords;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexDefinition;

/** 인플루언서 검색의 0건 검색어(#771, 대상별 #883). 생성자만 둔다 */
@Component
public class InfluencerZeroResultKeywords extends ZeroResultKeywords {

    public InfluencerZeroResultKeywords() {
        super(InfluencerIndexDefinition.METRIC_TARGET);
    }
}
