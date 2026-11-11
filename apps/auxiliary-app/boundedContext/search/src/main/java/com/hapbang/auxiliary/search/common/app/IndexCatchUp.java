package com.hapbang.auxiliary.search.common.app;

import java.time.LocalDateTime;

/**
 * 재색인 부품: 새 index로 alias를 옮긴 뒤, 채우는 동안 바뀐 원본을 새 index에 반영한다(#773, 공통 코드 #868). 채우는 동안 이벤트는 옛 index로
 * 갔으므로 이것이 없으면 그사이 변경을 놓친다. 대상마다 구현한다(인플루언서: 회원 변경분 증분 보정).
 * <p>
 * 정기 보정과 같은 락을 쓰면, 정기 보정이 돌고 있을 때 {@link CatchUp#BUSY}를 돌려준다. {@link Reindexer}가 잠시 기다렸다 다시 부른다.
 */
public interface IndexCatchUp {

    /**
     * 이 시각 뒤에 바뀐 원본을 반영한다. 늦게 커밋된 변경을 위한 여유는 부르는 쪽({@link Reindexer})이 이미 뺐다.
     *
     * @param from 이 시각 뒤에 바뀐 원본부터(원본 앱 시간대)
     */
    CatchUp runFrom(LocalDateTime from);

    /** 따라잡기 결과 */
    enum CatchUp {
        /** 끝까지 따라잡았다 */
        CAUGHT_UP,
        /** 다른 회차가 락을 쥐고 있어 돌지 못했다. 커서를 옮기지 않았다 */
        BUSY,
        /** 돌다가 실패했다. 커서는 옮긴 시각 이후에 있어 다음 정기 회차가 이어 간다 */
        FAILED
    }
}
