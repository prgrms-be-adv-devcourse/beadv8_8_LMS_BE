package com.hapbang.auxiliary.search.influencer.app;

import java.time.Clock;

import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.common.app.SearchJob;
import com.hapbang.auxiliary.search.common.app.SearchJobRunner;
import com.hapbang.auxiliary.search.common.app.TombstoneCleanup;
import com.hapbang.auxiliary.search.influencer.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexSetup;

/**
 * 인플루언서 검색 index의 tombstone 정리(#771, #868 R7). 인플루언서 index와 락({@link SearchJob#TOMBSTONE_CLEANUP})만 채운다.
 * <p>
 * <b>생성자만 둔다.</b> 정리 동작은 공통 {@link TombstoneCleanup}에서 바꾼다. 7일은 회원 이벤트 DLT 보존 기간이고, 그보다 늦게 다시 넣은 옛 이벤트는
 * 증분 보정(#772)이 다시 숨긴다.
 */
@Service
public class InfluencerTombstoneCleanup extends TombstoneCleanup {

    public InfluencerTombstoneCleanup(InfluencerIndexSetup indexSetup, InfluencerDocumentStore store,
            SearchJobRunner jobRunner) {
        super(indexSetup, store.writes(), jobRunner, SearchJob.TOMBSTONE_CLEANUP, Clock.systemDefaultZone());
    }
}
