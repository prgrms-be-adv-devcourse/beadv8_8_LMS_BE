package com.hapbang.auxiliary.chatbot.out;

import com.hapbang.auxiliary.chatbot.domain.CopyOutcome;

/**
 * 사본 한 행을 반영한 결과 (#716).
 *
 * @param outcome    반영 전 우리 행과 받은 값으로 정한 결과
 * @param ourVersion 반영 전 우리 version. 행이 없었거나 version 없는 데이터면 null
 */
public record CopyWrite(CopyOutcome outcome, Long ourVersion) {
}
