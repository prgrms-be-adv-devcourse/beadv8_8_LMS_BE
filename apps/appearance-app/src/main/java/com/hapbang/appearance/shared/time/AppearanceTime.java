package com.hapbang.appearance.shared.time;

import java.time.ZoneId;

/**
 * 출연 모듈의 시간대. 서버(EC2, Docker)의 기본 시간대가 UTC여도 출연 모듈의 모든 시각은 한국 시간으로 다룬다.
 */
public final class AppearanceTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private AppearanceTime() {
    }
}
