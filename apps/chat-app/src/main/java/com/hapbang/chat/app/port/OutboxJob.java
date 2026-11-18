package com.hapbang.chat.app.port;

import java.util.UUID;

/** 작업 큐에 적재할 outbox 행. 전달({@link DeliveryJob})과 연결 제어({@link ControlJob}) 두 종류다 */
public sealed interface OutboxJob permits DeliveryJob, ControlJob {

    long id();

    /** {@code deliveryId} 또는 {@code controlId} */
    UUID jobId();
}
