package com.hapbang.customersupport.app;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.customersupport.domain.HpReturnStatus;
import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.out.InquiryRepository;
import com.hapbang.shared.lecture.domain.LectureObjectionFailureCode;
import com.hapbang.shared.lecture.event.LectureObjectionRequestRefusedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 강의가 알려 준 이의 제기 결과를 문의에 기록한다 (#279, D60 (1)(4)(5)·D71). 결과 하나 = 고객센터 트랜잭션 하나이고 문의 행을 잠근다.
 * <ul>
 *     <li>접수 결과는 강의 내부 HTTP 응답({@link LectureObjectionFiler}), 판단·철회 결과와 반환 상태는 강의 이벤트로 온다.</li>
 *     <li>처리 중인 그 명령(명령 ID가 같음)일 때만 기록한다. 같은 결과가 다시 오거나 지난 명령의 결과가 늦게 오면 무시한다.</li>
 *     <li>HP 반환 상태는 앞으로만 바뀐다. 반환 이벤트가 판단 결과보다 먼저 와도 된다.</li>
 *     <li>모르는 문의의 결과는 기록할 곳이 없어 로그만 남긴다(실패로 다시 받지 않는다).</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class LectureObjectionResultUseCase {

    private final InquiryRepository inquiryRepository;

    /** 강의가 접수했다 */
    public Inquiry recordFiled(Long inquiryId) {
        Inquiry inquiry = lock(inquiryId);
        inquiry.markFiled();
        return inquiry;
    }

    /**
     * 강의가 접수를 거절했다.
     *
     * @param delete 요청 안에서 바로 거절되었는지. 그러면 사용자는 문의가 생기지 않은 것으로 보므로 문의를 삭제한다
     */
    public Inquiry recordFileRejected(Long inquiryId, LectureObjectionFailureCode failureCode, boolean delete) {
        Inquiry inquiry = lock(inquiryId);
        inquiry.rejectFiling(failureCode, delete, LocalDateTime.now());
        return inquiry;
    }

    public void apply(LectureObjectionResolvedEvent event) {
        applyTo(event.inquiryId(), event.orderItemId(), inquiry -> {
            if (!inquiry.applyResolved(event.commandId(), event.accepted(), LocalDateTime.now())) {
                ignored(event.commandId(), inquiry);
            }
        });
    }

    public void apply(LectureObjectionWithdrawnEvent event) {
        applyTo(event.inquiryId(), event.orderItemId(), inquiry -> {
            if (!inquiry.applyWithdrawn(event.commandId(), LocalDateTime.now())) {
                ignored(event.commandId(), inquiry);
            }
        });
    }

    public void apply(LectureObjectionRequestRefusedEvent event) {
        applyTo(event.inquiryId(), event.orderItemId(), inquiry -> {
            if (inquiry.applyRefused(event.commandId(), event.commandType(), event.failureCode())) {
                log.warn("강의가 이의 제기 명령을 거절해 운영 복구가 필요하다 inquiryId={}, commandId={}, failureCode={}",
                        inquiry.getId(), event.commandId(), event.failureCode());
            } else {
                ignored(event.commandId(), inquiry);
            }
        });
    }

    public void apply(LectureObjectionReturnCompletedEvent event) {
        applyTo(event.inquiryId(), event.orderItemId(), inquiry -> inquiry.advanceHpReturn(HpReturnStatus.COMPLETED));
    }

    public void apply(LectureObjectionReturnActionRequiredEvent event) {
        applyTo(event.inquiryId(), event.orderItemId(),
                inquiry -> inquiry.advanceHpReturn(HpReturnStatus.ACTION_REQUIRED));
    }

    private void applyTo(Long inquiryId, Long orderItemId, Consumer<Inquiry> change) {
        Optional<Inquiry> found = inquiryRepository.findForUpdate(inquiryId);
        if (found.isEmpty() || !found.get().isLectureDispute()
                || !Objects.equals(found.get().getTargetId(), orderItemId)) {
            log.error("강의 결과 이벤트에 맞는 이의 제기 문의가 없어 무시한다 inquiryId={}, orderItemId={}", inquiryId,
                    orderItemId);
            return;
        }
        change.accept(found.get());
    }

    /** 접수 결과를 기록할 문의. 커밋 뒤에 응답을 만들므로 답변도 지금 읽어 둔다 */
    private Inquiry lock(Long inquiryId) {
        Inquiry inquiry = inquiryRepository.findForUpdate(inquiryId)
                .orElseThrow(() -> new IllegalStateException("이의 제기 문의가 없습니다. inquiryId=" + inquiryId));
        inquiry.getReplies();
        return inquiry;
    }

    private static void ignored(String commandId, Inquiry inquiry) {
        log.info("처리 중인 명령의 결과가 아니라 무시한다(중복·늦은 결과) inquiryId={}, commandId={}, current={}/{}",
                inquiry.getId(), commandId, inquiry.getObjectionLinkStatus(), inquiry.getObjectionCommandId());
    }
}
