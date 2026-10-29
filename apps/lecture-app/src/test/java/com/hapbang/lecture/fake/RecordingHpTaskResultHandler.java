package com.hapbang.lecture.fake;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.app.LectureHpTaskResultHandler;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;

/**
 * HP 작업 실행기 테스트용 결과 처리기 (#275). 운영 처리기(#276·#277)가 아직 없는 목적에 대해 받은 결과를 남긴다.
 * {@code ORDER_HOLD}·{@code CANCEL}만 있다. 처리기가 없는 목적({@code PAYOUT} 등)은 실행기가 {@code DEAD}로 둔다.
 * 테스트마다 {@link #reset()}한다.
 */
public abstract class RecordingHpTaskResultHandler implements LectureHpTaskResultHandler {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;

    public void reset() {
        events.clear();
        failure = null;
    }

    /** 다음 결과 반영에서 던질 예외 (결과 기록 실패를 만든다) */
    public void failNext(RuntimeException failure) {
        this.failure = failure;
    }

    public List<String> events() {
        return List.copyOf(events);
    }

    @Override
    public void onSucceeded(LectureHpTask task) {
        record("succeeded:" + task.getRequestId());
    }

    @Override
    public void onRejected(LectureHpTask task) {
        record("rejected:" + task.getRequestId());
    }

    @Override
    public void onDead(LectureHpTask task) {
        record("dead:" + task.getRequestId());
    }

    private void record(String event) {
        RuntimeException next = failure;
        if (next != null) {
            failure = null;
            throw next;
        }
        events.add(event);
    }

    @Component
    public static class OrderHold extends RecordingHpTaskResultHandler {

        @Override
        public LectureHpTaskPurpose purpose() {
            return LectureHpTaskPurpose.ORDER_HOLD;
        }
    }

    @Component
    public static class Cancel extends RecordingHpTaskResultHandler {

        @Override
        public LectureHpTaskPurpose purpose() {
            return LectureHpTaskPurpose.CANCEL;
        }
    }
}
