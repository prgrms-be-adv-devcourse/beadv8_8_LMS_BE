package com.hapbang.auxiliary.chatbot.out;

import com.hapbang.auxiliary.chatbot.domain.CopyOutcome;

/**
 * 사본 한 행을 반영한 결과 (#716).
 *
 * @param outcome    반영 전 우리 행과 받은 값으로 정한 결과
 * @param ourVersion 반영 전 우리 version. 행이 없었거나 version 없는 데이터면 null
 * @param restored   원본 없음으로 표시돼 있던 행을 싱크가 다시 봐서 표시를 풀었는지(#718). 값이 같아도 색인 상태는 바뀐다
 */
public record CopyWrite(CopyOutcome outcome, Long ourVersion, boolean restored) {

    public CopyWrite(CopyOutcome outcome, Long ourVersion) {
        this(outcome, ourVersion, false);
    }
}
