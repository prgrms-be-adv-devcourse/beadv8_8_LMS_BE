package com.hapbang.customersupport.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.hapbang.customersupport.app.exception.CustomerSupportErrorCode;
import com.hapbang.customersupport.app.exception.CustomerSupportException;
import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.ObjectionLinkStatus;
import com.hapbang.customersupport.out.InquiryRepository;
import com.hapbang.shared.lecture.domain.LectureObjectionFailureCode;
import com.hapbang.shared.lecture.dto.LectureObjectionFileRequest;
import com.hapbang.shared.lecture.out.LectureApiCallException;
import com.hapbang.shared.lecture.out.LectureApiClient;

import lombok.extern.slf4j.Slf4j;

/**
 * 강의 내용 다름 이의 제기를 강의 내부 HTTP로 접수한다 (#279, D60 (1)·D71). 트랜잭션을 열지 않는다.
 * <ul>
 *     <li>문의를 커밋한 뒤 요청 안에서 한 번 시도한다({@link #fileNow}). 강의가 접수하면 접수됨, 거절하면 접수 불가를 기록한다.
 *     요청 안의 거절은 문의를 삭제한 것까지 커밋한 뒤에만 4xx로 응답한다. 결과를 모르거나 기록하지 못하면 접수 처리 중(202)으로 둔다.</li>
 *     <li>접수 처리 중으로 남은 문의는 재시도 배치가 같은 명령 ID로 다시 보낸다({@link #retryDue}). 강의가 처음 결과를 돌려주므로
 *     두 번 접수되지 않는다. 처리 중을 본 뒤의 거절은 문의를 남겨 "접수 불가 · 사유"로 보여 준다.</li>
 *     <li>강의 응답의 {@code code}가 이의 제기 거절 사유가 아니면(내부 API 키 오류 등) 설정 문제라 거절로 보지 않고 처리 중으로 둔다.</li>
 *     <li>{@code hapbang.clients.lecture.base-url}이 없어 Client가 없으면 처리 중으로 두고 오류 로그를 남긴다.</li>
 * </ul>
 */
@Slf4j
@Component
public class LectureObjectionFiler {

    private final Supplier<LectureApiClient> client;
    private final LectureObjectionResultUseCase lectureObjectionResultUseCase;
    private final InquiryRepository inquiryRepository;
    private final CustomerSupportBatchProperties properties;

    @Autowired
    LectureObjectionFiler(ObjectProvider<LectureApiClient> client,
                          LectureObjectionResultUseCase lectureObjectionResultUseCase,
                          InquiryRepository inquiryRepository, CustomerSupportBatchProperties properties) {
        this((Supplier<LectureApiClient>) client::getIfAvailable, lectureObjectionResultUseCase, inquiryRepository,
                properties);
    }

    LectureObjectionFiler(Supplier<LectureApiClient> client,
                          LectureObjectionResultUseCase lectureObjectionResultUseCase,
                          InquiryRepository inquiryRepository, CustomerSupportBatchProperties properties) {
        this.client = client;
        this.lectureObjectionResultUseCase = lectureObjectionResultUseCase;
        this.inquiryRepository = inquiryRepository;
        this.properties = properties;
    }

    /**
     * 막 저장한 이의 제기 문의를 접수한다.
     *
     * @return 결과를 기록한 문의. 접수 처리 중이면 넘겨받은 문의 그대로다
     * @throws CustomerSupportException 강의가 거절했고 그 거절(문의 삭제)을 기록했다
     */
    public Inquiry fileNow(Inquiry inquiry) {
        Optional<LectureObjectionFailureCode> outcome;
        try {
            outcome = file(inquiry);
        } catch (UnknownResult unknown) {
            return inquiry;
        }
        if (outcome.isEmpty()) {
            return record(inquiry, () -> lectureObjectionResultUseCase.recordFiled(inquiry.getId())).orElse(inquiry);
        }
        LectureObjectionFailureCode failureCode = outcome.get();
        if (record(inquiry, () -> lectureObjectionResultUseCase.recordFileRejected(inquiry.getId(), failureCode, true))
                .isEmpty()) {
            return inquiry;
        }
        throw new CustomerSupportException(CustomerSupportErrorCode.of(failureCode));
    }

    /**
     * 접수 처리 중으로 남은 문의를 다시 접수한다(배치). 마지막으로 바뀐 지 대기 시간이 지난 문의만, 오래된 문의부터 {@code batchSize}건.
     * 한 건이 실패해도 다음 문의를 처리한다.
     *
     * @return 결과를 기록한 문의 수
     */
    public int retryDue(LocalDateTime now) {
        List<Long> inquiryIds = inquiryRepository.findFilingIdsUpdatedBefore(now.minus(properties.pendingGrace()),
                PageRequest.of(0, properties.batchSize()));
        int recorded = 0;
        for (Long inquiryId : inquiryIds) {
            try {
                if (retry(inquiryId)) {
                    recorded++;
                }
            } catch (RuntimeException e) {
                log.warn("이의 제기 접수 재시도 실패, 다음 배치에서 다시 한다 inquiryId={}", inquiryId, e);
            }
        }
        return recorded;
    }

    private boolean retry(Long inquiryId) {
        Optional<Inquiry> found = inquiryRepository.findById(inquiryId)
                .filter(inquiry -> inquiry.getObjectionLinkStatus() == ObjectionLinkStatus.FILING);
        if (found.isEmpty()) {
            return false;
        }
        Optional<LectureObjectionFailureCode> outcome;
        try {
            outcome = file(found.get());
        } catch (UnknownResult unknown) {
            return false;
        }
        if (outcome.isEmpty()) {
            lectureObjectionResultUseCase.recordFiled(inquiryId);
        } else {
            // 사용자는 처리 중을 본 적이 있으므로 문의를 지우지 않는다
            lectureObjectionResultUseCase.recordFileRejected(inquiryId, outcome.get(), false);
        }
        return true;
    }

    /**
     * 강의에 접수를 보낸다. 접수되면 빈 값, 거절되면 사유다.
     *
     * @throws UnknownResult 결과를 모른다(호출 불가·계약에 없는 오류·Client 없음)
     */
    private Optional<LectureObjectionFailureCode> file(Inquiry inquiry) {
        LectureApiClient lectureApiClient = client.get();
        if (lectureApiClient == null) {
            log.error("강의 내부 API Client가 없어 이의 제기를 접수하지 못했다(hapbang.clients.lecture.base-url 미설정) "
                    + "inquiryId={}", inquiry.getId());
            throw new UnknownResult();
        }
        try {
            lectureApiClient.fileObjection(LectureObjectionFileRequest.of(inquiry.getId(), inquiry.getTargetId(),
                    inquiry.getUserId(), inquiry.getContent()));
            return Optional.empty();
        } catch (LectureApiCallException e) {
            Optional<LectureObjectionFailureCode> failureCode = e.getReason() == LectureApiCallException.Reason.REJECTED
                    ? LectureObjectionFailureCode.from(e.getCode()) : Optional.empty();
            if (failureCode.isPresent()) {
                return failureCode;
            }
            if (e.getReason() == LectureApiCallException.Reason.REJECTED) {
                log.error("강의가 이의 제기 접수를 계약에 없는 오류로 거절했다(설정 확인 필요) inquiryId={}, status={}, code={}",
                        inquiry.getId(), e.getStatus(), e.getCode());
            } else {
                log.warn("강의 이의 제기 접수 결과를 받지 못했다. 접수 처리 중으로 두고 다시 시도한다 inquiryId={}, status={}",
                        inquiry.getId(), e.getStatus(), e);
            }
            throw new UnknownResult();
        } catch (RuntimeException e) {
            // Client가 감싸지 못한 예외도 강의가 처리했는지 알 수 없다
            log.warn("강의 이의 제기 접수 중 예외. 접수 처리 중으로 두고 다시 시도한다 inquiryId={}", inquiry.getId(), e);
            throw new UnknownResult();
        }
    }

    /** 결과를 기록한다. 기록하지 못하면 빈 값이고 문의는 접수 처리 중으로 남는다(재시도 배치가 같은 명령으로 다시 보낸다) */
    private Optional<Inquiry> record(Inquiry inquiry, Supplier<Inquiry> record) {
        try {
            return Optional.of(record.get());
        } catch (RuntimeException e) {
            log.warn("이의 제기 접수 결과를 기록하지 못했다. 접수 처리 중으로 둔다 inquiryId={}", inquiry.getId(), e);
            return Optional.empty();
        }
    }

    /** 강의 접수 결과를 모른다 */
    private static final class UnknownResult extends RuntimeException {

        UnknownResult() {
            super(null, null, false, false);
        }
    }
}
