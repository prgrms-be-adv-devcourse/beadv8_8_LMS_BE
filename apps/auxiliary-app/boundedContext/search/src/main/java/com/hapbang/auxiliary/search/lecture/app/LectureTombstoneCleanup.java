package com.hapbang.auxiliary.search.lecture.app;

import java.time.Clock;

import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.common.app.SearchJob;
import com.hapbang.auxiliary.search.common.app.SearchJobRunner;
import com.hapbang.auxiliary.search.common.app.TombstoneCleanup;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexSetup;

/**
 * 강의 검색 index의 tombstone 정리(#882). 강의 index와 락({@link SearchJob#LECTURE_TOMBSTONE_CLEANUP})만 채운다.
 * <p>
 * <b>생성자만 둔다.</b> 정리 동작은 공통 {@link TombstoneCleanup}에서 바꾼다(#868 R7). 지우는 것은 판매 중이 아닌 강의(숨김 문서)뿐이다. 판매자가
 * 정지된 강의는 숨김이 아니라 {@code sellerActive=false}라 정지가 길어도 지우지 않는다(Q2). 7일은 강의 이벤트 DLT 보존 기간이다.
 */
@Service
public class LectureTombstoneCleanup extends TombstoneCleanup {

    public LectureTombstoneCleanup(LectureIndexSetup indexSetup, LectureDocumentStore store,
            SearchJobRunner jobRunner) {
        super(indexSetup, store.writes(), jobRunner, SearchJob.LECTURE_TOMBSTONE_CLEANUP, Clock.systemDefaultZone());
    }
}
