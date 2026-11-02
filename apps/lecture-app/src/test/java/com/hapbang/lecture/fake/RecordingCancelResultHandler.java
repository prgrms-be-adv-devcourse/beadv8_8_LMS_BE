package com.hapbang.lecture.fake;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.hapbang.lecture.app.CancelResultHandler;
import com.hapbang.lecture.domain.LectureHpTask;

/**
 * HP 작업 실행기·배치 테스트용 {@code CANCEL} 결과 처리기 (#275, #277). 주문 상태를 바꾸지 않고 받은 결과만 남긴다.
 * 실행기 자체를 확인하는 테스트가 {@code @TestBean}으로 운영 처리기({@link CancelResultHandler}) 자리에 넣는다.
 * 그래서 작업의 주문·항목이 실제로 없어도 된다. 테스트마다 {@link #reset()}한다.
 */
public class RecordingCancelResultHandler extends CancelResultHandler {

    /**
     * {@code @TestBean(methodName = FACTORY)}로 쓴다. 테스트 클래스들이 같은 팩토리를 쓰면 Spring 테스트 컨텍스트를 함께 써서
     * DB 연결이 늘지 않는다
     */
    public static final String FACTORY = "com.hapbang.lecture.fake.RecordingCancelResultHandler#create";

    private final List<String> events = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;

    public RecordingCancelResultHandler() {
        super(null);
    }

    static CancelResultHandler create() {
        return new RecordingCancelResultHandler();
    }

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
}
