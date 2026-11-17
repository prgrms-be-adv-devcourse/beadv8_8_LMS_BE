package com.hapbang.chat.app;

import org.springframework.stereotype.Service;

import com.hapbang.chat.app.port.ChatDeliveryQueue;
import com.hapbang.chat.global.config.ConditionalOnChatDeliveryPipeline;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatDeliveryPipelineService} 구현. 각 작업을 그 일을 맡은 app 조정 서비스와 큐 계약에 그대로 넘긴다.
 */
@Service
@ConditionalOnChatDeliveryPipeline
@RequiredArgsConstructor
public class DefaultChatDeliveryPipelineService implements ChatDeliveryPipelineService {

    private final ChatOutboxRelay relay;
    private final ChatStreamJobProcessor processor;
    private final ChatStreamReconstructor reconstructor;
    private final ChatStreamTrimmer trimmer;
    private final ChatDeliveryQueue queue;
    private final ChatDeliveryAvailability availability;

    @Override
    public void resetAvailability() {
        availability.reset();
    }

    @Override
    public void ensureGroup(ChatJobStream stream) {
        queue.ensureGroup(stream);
    }

    @Override
    public int relayBatch(ChatJobStream stream) {
        return relay.relayBatch(stream);
    }

    @Override
    public int pollNew(ChatJobStream stream, String consumer) {
        return processor.pollNew(stream, consumer);
    }

    @Override
    public int reclaim(ChatJobStream stream, String consumer, int budget) {
        return processor.reclaim(stream, consumer, budget);
    }

    @Override
    public int reconstructIfDue(ChatJobStream stream) {
        return reconstructor.reconstructIfDue(stream);
    }

    @Override
    public long trim(ChatJobStream stream) {
        return trimmer.trim(stream);
    }

    @Override
    public void releaseConsumer(String consumer) {
        queue.releaseConsumer(consumer);
    }
}
