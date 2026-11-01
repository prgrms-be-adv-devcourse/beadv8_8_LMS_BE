package com.hapbang.chat.app;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatDeliverySourceReader.MessageSource;
import com.hapbang.chat.app.ChatOutboxStore.ControlJob;
import com.hapbang.chat.app.ChatOutboxStore.DeliveryJob;
import com.hapbang.chat.app.dto.ControlFanout;
import com.hapbang.chat.app.dto.MessageFanout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 커밋된 전달·제어 작업으로 Pub/Sub payload를 만든다. 자기 chat DB의 원본(outbox·메시지·불변 수신자 집합)만 읽는다.
 * <ul>
 *     <li>수신자는 펼치지 않고 outbox가 가리키는 집합의 버전만 싣는다. 받는 앱이 버전으로 참여 상태를 다시 읽을지 정한다.</li>
 *     <li>메시지나 집합 헤더가 없으면 빈 값을 돌려준다. 호출한 쪽이 격리한다.</li>
 *     <li>원본은 만든 뒤 바뀌지 않으므로 따로 읽는다. 발행 전에 모든 읽기가 끝난다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatFanoutPayloadLoader {

    private final ChatOutboxStore chatOutboxStore;
    private final ChatDeliverySourceReader sourceReader;

    /** 메시지 전달 payload. outbox·메시지·수신자 집합 중 하나라도 없으면 빈 값 */
    public Optional<MessageFanout> loadMessage(UUID deliveryId) {
        return chatOutboxStore.findJob(ChatJobStream.DELIVERY, deliveryId)
                .map(DeliveryJob.class::cast)
                .flatMap(this::toMessageFanout);
    }

    /** 연결 제어 payload. 제어 outbox가 없으면 빈 값 */
    public Optional<ControlFanout> loadControl(UUID controlId) {
        return chatOutboxStore.findJob(ChatJobStream.CONTROL, controlId)
                .map(ControlJob.class::cast)
                .map(job -> new ControlFanout(job.jobId(), job.type(), job.userId(), job.memberVersion(),
                        job.reason()));
    }

    private Optional<MessageFanout> toMessageFanout(DeliveryJob job) {
        Optional<MessageSource> found = sourceReader.findMessage(job.messageId());
        OptionalInt setVersion = sourceReader.findRecipientSetVersion(job.recipientSetId());
        if (found.isEmpty() || setVersion.isEmpty()) {
            // 메시지나 수신자 집합 헤더가 없다. 현재 참여로 바꿔 보내지 않고 호출한 쪽이 격리한다(설계 4.6 4번)
            log.error("전달 원본이 없어 전달하지 않음 deliveryId={}, messageId={}, recipientSetId={}", job.jobId(),
                    job.messageId(), job.recipientSetId());
            return Optional.empty();
        }
        MessageSource message = found.get();
        return Optional.of(new MessageFanout(job.jobId(), MessageFanout.Type.valueOf(job.type().name()),
                job.roomId(), setVersion.getAsInt(), message.message(), job.notifyRule(), message.clientMessageId()));
    }
}
