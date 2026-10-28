package com.hapbang.shared.appearance.event;

import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;

/** 게스트가 출연 모집글에 지원했다. 지원 하나에 한 번 발행된다 */
public record AppearanceApplicationSubmittedEvent(AppearanceApplicationDto application) {
}
