package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 전달 파이프라인의 현재 시각. DB({@code TIMESTAMP(6)})와 같은 마이크로초 단위로 잘라, 저장한 값과 다시 비교할 수 있게 한다.
 */
public final class ChatDeliveryClock {

    private ChatDeliveryClock() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
