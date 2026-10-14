package com.hapbang.customersupport.fake;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException;
import com.hapbang.shared.lecture.port.LectureObjectionPort;

/**
 * 테스트용 {@link LectureObjectionPort}. 고객센터는 강의 모듈에 Gradle 의존을 걸지 않으므로 모듈 테스트에서는 이 Fake가 빈이 된다.
 * 호출을 기록하고, {@link #refuseNext}로 다음 호출을 거절하게 할 수 있다. 각 테스트가 끝나면 {@link #reset}한다.
 */
@Component
public class FakeLectureObjectionPort implements LectureObjectionPort {

    public record Filed(Long orderItemId, Long buyerUserId, Long inquiryId, String reason) {
    }

    public record Resolved(Long orderItemId, Long inquiryId, boolean accepted, Long adminUserId) {
    }

    private final List<Filed> filed = new ArrayList<>();
    private final List<Resolved> resolved = new ArrayList<>();
    private LectureObjectionRefusedException.Reason refuseNext;

    @Override
    public void fileObjection(Long orderItemId, Long buyerUserId, Long inquiryId, String reason) {
        refuseIfRequested();
        filed.add(new Filed(orderItemId, buyerUserId, inquiryId, reason));
    }

    @Override
    public void resolveObjection(Long orderItemId, Long inquiryId, boolean accepted, Long adminUserId) {
        refuseIfRequested();
        resolved.add(new Resolved(orderItemId, inquiryId, accepted, adminUserId));
    }

    public void refuseNext(LectureObjectionRefusedException.Reason reason) {
        this.refuseNext = reason;
    }

    public List<Filed> filed() {
        return List.copyOf(filed);
    }

    public List<Resolved> resolved() {
        return List.copyOf(resolved);
    }

    public void reset() {
        filed.clear();
        resolved.clear();
        refuseNext = null;
    }

    private void refuseIfRequested() {
        if (refuseNext != null) {
            LectureObjectionRefusedException.Reason reason = refuseNext;
            refuseNext = null;
            throw new LectureObjectionRefusedException(reason);
        }
    }
}
