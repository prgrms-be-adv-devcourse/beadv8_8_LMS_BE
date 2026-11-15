package com.hapbang.auxiliary.chatbot.domain;

import java.util.Objects;

/**
 * 받은 값(이벤트·싱크)을 사본에 반영한 결과 (#716). 이벤트와 싱크가 같은 판정을 쓴다.
 * <p>
 * version 있는 데이터(출연):
 * <table>
 *     <tr><th>받은 것 vs 우리</th><th>처리</th><th>결과</th></tr>
 *     <tr><td>우리 행 없음</td><td>넣는다</td><td>{@link #INSERTED}</td></tr>
 *     <tr><td>version 큼, 해시 다름</td><td>반영</td><td>{@link #SOURCE_NEWER}</td></tr>
 *     <tr><td>version 큼, 해시 같음</td><td>version만 올린다(원본이 DTO 밖 필드만 바꿈, 정상)</td><td>{@link #VERSION_ONLY}</td></tr>
 *     <tr><td>version 같음, 해시 같음</td><td>없음</td><td>{@link #UNCHANGED}</td></tr>
 *     <tr><td>version 같음, 해시 다름</td><td>원본으로 고친다</td><td>{@link #SAME_VERSION_DIFFERENT}</td></tr>
 *     <tr><td>version 작음</td><td>무시</td><td>{@link #OURS_NEWER}</td></tr>
 * </table>
 * version 없는 데이터(출연 리뷰)는 없으면 넣고, 해시가 같으면 없음, 다르면 {@link #SAME_VERSION_DIFFERENT}로 고친다.
 * <p>
 * version이 비어 있을 수 있는 데이터(회원, {@code UserDto} 규칙, #717): 받은 version이 없는데 우리 행에 있으면 버전 도입 전의 옛 정보로 보고
 * 무시하고({@link #OURS_NEWER}), 우리 행에 version이 없는데 받은 것에 있으면 원본 새것으로 본다.
 * <p>
 * 마스킹한 회원의 데이터(#717)는 반영하지 않고 {@link #DISCARDED}로 센다. 어긋남·경고 집계에 넣지 않는다. 마스킹 회원의 회원 행은
 * 역할·상태만 반영하지만 분야·자기소개를 버리므로 역시 {@link #DISCARDED}로 센다.
 */
public enum CopyOutcome {

    INSERTED(SyncMismatchKind.ONLY_IN_SOURCE, true),
    SOURCE_NEWER(SyncMismatchKind.SOURCE_NEWER, true),
    VERSION_ONLY(null, false),
    UNCHANGED(null, false),
    SAME_VERSION_DIFFERENT(SyncMismatchKind.SAME_VERSION_DIFFERENT, true),
    OURS_NEWER(SyncMismatchKind.OURS_NEWER, false),
    /** 마스킹한 회원의 데이터라 버렸다(회원 행은 역할·상태만 반영) (#717) */
    DISCARDED(null, false);

    /** 싱크 회차에 남길 어긋남. 정상이면 null */
    private final SyncMismatchKind mismatch;
    /** 사본의 값(해시)이 바뀌었는지 */
    private final boolean valueChanged;

    CopyOutcome(SyncMismatchKind mismatch, boolean valueChanged) {
        this.mismatch = mismatch;
        this.valueChanged = valueChanged;
    }

    /**
     * 반영 전 우리 행과 받은 값으로 결과를 정한다.
     *
     * @param ourVersion      반영 전 우리 version. 행이 없거나 version 없는 데이터면 null
     * @param ourHash         반영 전 우리 해시. 행이 없으면 null
     * @param incomingVersion 받은 version. version 없는 데이터면 null
     * @param incomingHash    받은 값의 해시
     */
    public static CopyOutcome decide(Long ourVersion, String ourHash, Long incomingVersion, String incomingHash) {
        Objects.requireNonNull(incomingHash, "incomingHash");
        if (ourHash == null) {
            return INSERTED;
        }
        boolean sameHash = ourHash.equals(incomingHash);
        if (incomingVersion == null && ourVersion == null) {
            return sameHash ? UNCHANGED : SAME_VERSION_DIFFERENT;
        }
        if (incomingVersion == null) {
            return OURS_NEWER;
        }
        if (ourVersion == null) {
            return sameHash ? VERSION_ONLY : SOURCE_NEWER;
        }
        int compared = Long.compare(incomingVersion, ourVersion);
        if (compared > 0) {
            return sameHash ? VERSION_ONLY : SOURCE_NEWER;
        }
        if (compared < 0) {
            return OURS_NEWER;
        }
        return sameHash ? UNCHANGED : SAME_VERSION_DIFFERENT;
    }

    /** 싱크 회차에 남길 어긋남. 정상이면 null */
    public SyncMismatchKind mismatch() {
        return mismatch;
    }

    /** 사본 값을 새로 넣거나 고쳤는지(경고 비율의 분자: 넣은 건수 + 고친 건수) */
    public boolean valueChanged() {
        return valueChanged;
    }

    /** 고친 건수에 넣는지(원본 새것 + 같은 version 다른 값). version만 오른 정상 건은 넣지 않는다 */
    public boolean fixed() {
        return this == SOURCE_NEWER || this == SAME_VERSION_DIFFERENT;
    }
}
