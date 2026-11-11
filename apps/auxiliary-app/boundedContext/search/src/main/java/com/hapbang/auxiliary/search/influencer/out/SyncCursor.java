package com.hapbang.auxiliary.search.influencer.out;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 증분 보정 커서 한 행(#772, {@code search.sync_cursor}). 회원 변경분 내부 API(#769)를 어디까지 읽었는지다.
 * <ul>
 *     <li>커서는 마지막으로 반영한 회원의 (updatedAt, 회원 ID)다. 회원 앱이 준 값을 해석하지 않고 그대로 넘긴다.</li>
 *     <li>다음 회차는 {@link #readFrom}(커서 시각 − 겹쳐 읽기, 회원 ID {@value #READ_FROM_ID})부터 읽는다. 늦게 커밋된 변경이 지나간 커서 앞에
 *     나타나기 때문이다({@code docs/api/internal-members.md} 4장). 겹쳐 읽은 회원은 version으로 걸러진다.</li>
 *     <li>{@code caughtUpAt}은 마지막으로 끝(hasNext=false)까지 읽은 시각이다. 변경이 없어도 회차가 돌면 바뀐다. 커서 지연 지표는 커서 시각이
 *     아니라 이 값으로 잰다(#772 Q3: 커서 시각은 변경이 없는 동안 멈춰 있어 보정이 정상이어도 지연이 커 보인다).</li>
 * </ul>
 *
 * @param name            읽는 대상({@link #MEMBER_CHANGES})
 * @param memberUpdatedAt 마지막으로 반영한 회원의 updatedAt(회원 앱 시간대 Asia/Seoul)
 * @param memberId        마지막으로 반영한 회원 ID
 * @param caughtUpAt      마지막으로 끝까지 읽은 시각(KST). 아직 없으면 null
 */
public record SyncCursor(String name, LocalDateTime memberUpdatedAt, long memberId, LocalDateTime caughtUpAt) {

    /** 회원 변경분 커서 이름 */
    public static final String MEMBER_CHANGES = "member-changes";

    /** 겹쳐 읽을 때의 회원 ID. 겹친 시간에 바뀐 회원을 모두 다시 읽는다 */
    public static final long READ_FROM_ID = 0L;

    /**
     * 이번 회차에 읽기 시작할 시각. 커서 시각에서 겹쳐 읽기만큼 뺀다(회원 ID는 {@value #READ_FROM_ID}부터).
     *
     * @param overlap 겹쳐 읽는 시간(1분)
     */
    public LocalDateTime readFrom(Duration overlap) {
        return memberUpdatedAt.minus(overlap);
    }
}
