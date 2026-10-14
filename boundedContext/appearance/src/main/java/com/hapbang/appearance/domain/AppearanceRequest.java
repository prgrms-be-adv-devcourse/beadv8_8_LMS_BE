package com.hapbang.appearance.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 출연 요청서. 호스트가 게스트 1명에게 보내는 출연 요청이다.
 * <ul>
 *     <li>보낼 때 출연료만큼 HP를 보류한다. 저장 직후는 {@code HOLD_PENDING}이고, 보류 결과에 따라
 *     {@code REQUESTED} 또는 {@code HOLD_FAILED}가 된다.</li>
 *     <li>보낸 뒤 수정할 수 있는 것은 제목·콘텐츠 설명·호스트 채널 URL뿐이며, 게스트 응답 전({@code REQUESTED})까지만 가능하다.</li>
 *     <li>입력 값 제한은 {@link AppearanceContentRules}(요청서·모집글 공통)와 {@link AppearanceRequestRules}(거절 사유)에 있고, 만들 때와 수정할 때 이 클래스에서도 다시 확인한다
 *     (요청 DTO 검증을 거치지 않는 호출도 막기 위해).</li>
 * </ul>
 */
@Entity
@Table(name = "requests", schema = "appearances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppearanceRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 요청을 보낸 호스트 회원 ID */
    private Long hostId;

    /** 요청을 받는 게스트 회원 ID. 호스트와 같을 수 없다 */
    private Long guestId;

    /** 기본 정보(제목·호스트 채널 URL·콘텐츠 설명). 등록 후 수정할 수 있는 값 */
    @Embedded
    private AppearanceBasicInfo basicInfo;

    /** 방송 예정 일시 */
    private LocalDateTime scheduledAt;

    /** 예상 출연 시간(분). 1 ~ 1,440 */
    private int expectedMinutes;


    /** 출연료 (HP). 최저 출연료({@link AppearancePolicy#minimumFee()}) 이상 */
    private long fee;

    /** 수락 기한. 현재 이후이고 방송 예정 일시보다 앞서야 한다. 이 시각까지 게스트가 응답하지 않으면 만료된다 */
    private LocalDateTime acceptDeadline;

    @Enumerated(EnumType.STRING)
    private AppearanceRequestStatus status;

    /** 게스트 거절 사유 (게스트 응답 처리에서 사용) */
    private String rejectReason;

    /** 게스트 응답(수락·거절) 시각 (게스트 응답 처리에서 사용) */
    private LocalDateTime respondedAt;

    /** 호스트 취소 시각 (취소 처리에서 사용) */
    private LocalDateTime canceledAt;

    /** 낙관적 락. 동시에 수정·응답할 때 나중 것을 막는다 */
    @Version
    private Long version;

    private AppearanceRequest(Long hostId, Long guestId, AppearanceBasicInfo basicInfo,
                              AppearanceRequestContent requestContent) {
        this.hostId = hostId;
        this.guestId = guestId;
        this.basicInfo = basicInfo;
        this.scheduledAt = requestContent.scheduledAt();
        this.expectedMinutes = requestContent.expectedMinutes();
        this.fee = requestContent.fee();
        this.acceptDeadline = requestContent.acceptDeadline();
        this.status = AppearanceRequestStatus.HOLD_PENDING;
    }

    /**
     * 출연 요청서를 만든다. HP 보류 전이라 {@code HOLD_PENDING} 상태다.
     *
     * @throws AppearanceException 입력 값 형식 오류({@code INVALID_REQUEST}), 자기 자신에게 요청({@code CANNOT_REQUEST_SELF}),
     *                             최저 출연료 미달({@code FEE_BELOW_MINIMUM}), 기한 순서 오류({@code INVALID_REQUEST_SCHEDULE})
     */
    public static AppearanceRequest create(Long hostId, Long guestId, AppearanceRequestContent requestContent,
                                           AppearancePolicy policy, LocalDateTime now) {
        AppearanceBasicInfo basicInfo = AppearanceBasicInfo.of(requestContent.title(), requestContent.hostChannelUrl(),
                requestContent.content());
        AppearanceContentRules.validateExpectedMinutes(requestContent.expectedMinutes());
        if (hostId.equals(guestId)) {
            throw new AppearanceException(AppearanceErrorCode.CANNOT_REQUEST_SELF);
        }
        if (policy.isBelowMinimumFee(requestContent.fee())) {
            throw new AppearanceException(AppearanceErrorCode.FEE_BELOW_MINIMUM,
                    "출연료는 최저 출연료(%,d HP) 이상이어야 합니다.".formatted(policy.minimumFee()));
        }
        // 현재 < 수락 기한 < 방송 예정 일시
        if (!requestContent.acceptDeadline().isAfter(now)
                || !requestContent.acceptDeadline().isBefore(requestContent.scheduledAt())) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST_SCHEDULE);
        }
        return new AppearanceRequest(hostId, guestId, basicInfo, requestContent);
    }

    /** HP 보류 성공 → 게스트 응답 대기 */
    public void markRequested() {
        requireStatus(AppearanceRequestStatus.HOLD_PENDING);
        this.status = AppearanceRequestStatus.REQUESTED;
    }

    /** 잔액 부족으로 HP 보류 실패 */
    public void markHoldFailed() {
        requireStatus(AppearanceRequestStatus.HOLD_PENDING);
        this.status = AppearanceRequestStatus.HOLD_FAILED;
    }

    /**
     * 기본 정보(제목·콘텐츠 설명·호스트 채널 URL)를 수정한다. null인 값은 바꾸지 않는다.
     * 보낸 값으로 덮어쓰기만 하므로 같은 수정을 여러 번 해도 결과가 같다(멱등).
     *
     * @throws AppearanceException 호스트가 아님({@code NOT_REQUEST_HOST}),
     *                             게스트 응답 전이 아님({@code APPEARANCE_REQUEST_NOT_EDITABLE}), 입력 값 형식 오류({@code INVALID_REQUEST})
     */
    public void updateBasicInfo(Long userId, String title, String content, String hostChannelUrl) {
        if (!isHost(userId)) {
            throw new AppearanceException(AppearanceErrorCode.NOT_REQUEST_HOST);
        }
        if (status != AppearanceRequestStatus.REQUESTED) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_REQUEST_NOT_EDITABLE);
        }
        this.basicInfo = basicInfo.withChanges(title, content, hostChannelUrl);
    }

    /**
     * 게스트가 수락한다. 수락 후 출연({@link Appearance})이 성립하며, HP는 이동하지 않는다(보류 유지).
     *
     * @param viewedVersion 게스트가 화면에서 본 요청서 버전. 그사이 호스트가 수정했으면 수락을 막는다
     *                      (게스트가 본 조건 그대로 동의하도록)
     * @throws AppearanceException 게스트가 아님({@code NOT_REQUEST_GUEST}), 응답 대기 중이 아님
     *                             ({@code APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE}), 수락 기한 지남({@code APPEARANCE_REQUEST_EXPIRED}),
     *                             요청서가 수정됨({@code APPEARANCE_REQUEST_MODIFIED})
     */
    public void accept(Long guestId, Long viewedVersion, LocalDateTime now) {
        requireRespondable(isGuest(guestId), AppearanceErrorCode.NOT_REQUEST_GUEST, now);
        if (!Objects.equals(version, viewedVersion)) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_REQUEST_MODIFIED);
        }
        this.status = AppearanceRequestStatus.ACCEPTED;
        this.respondedAt = now;
    }

    /**
     * 게스트가 거절한다. 보류 HP는 호스트에게 반환해야 한다 (호출한 쪽이 HP 작업을 기록한다).
     *
     * @param reason 거절 사유 (선택, 500자 이하)
     * @throws AppearanceException 게스트가 아님, 응답 대기 중이 아님, 수락 기한 지남, 사유 형식 오류({@code INVALID_REQUEST})
     */
    public void reject(Long guestId, String reason, LocalDateTime now) {
        requireRespondable(isGuest(guestId), AppearanceErrorCode.NOT_REQUEST_GUEST, now);
        AppearanceRequestRules.validateRejectReason(reason);
        this.status = AppearanceRequestStatus.REJECTED;
        this.rejectReason = reason;
        this.respondedAt = now;
    }

    /**
     * 호스트가 게스트 응답 전에 취소한다 (응답 전 취소). 보류 HP는 호스트에게 반환해야 한다.
     * 출연 성립 후의 호스트 일방 취소(위약금)와 다르다.
     *
     * @throws AppearanceException 호스트가 아님({@code NOT_REQUEST_HOST}), 응답 대기 중이 아님, 수락 기한 지남
     */
    public void cancel(Long hostId, LocalDateTime now) {
        if (!isHost(hostId)) {
            throw new AppearanceException(AppearanceErrorCode.NOT_REQUEST_HOST, "출연 요청서를 보낸 호스트만 취소할 수 있습니다.");
        }
        requireRespondable(true, AppearanceErrorCode.NOT_REQUEST_HOST, now);
        this.status = AppearanceRequestStatus.CANCELED;
        this.canceledAt = now;
    }

    /**
     * 수락·거절·응답 전 취소가 가능한지 확인한다. 순서: 행위자 → 상태 → 수락 기한.
     * 수락 기한은 DB 상태가 아니라 현재 시각으로 확인한다. 만료 처리(상태 변경·반환)가 아직 안 됐어도 기한이 지나면 막는다.
     */
    private void requireRespondable(boolean isActor, AppearanceErrorCode notActorError, LocalDateTime now) {
        if (!isActor) {
            throw new AppearanceException(notActorError);
        }
        if (status != AppearanceRequestStatus.REQUESTED) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE);
        }
        if (!now.isBefore(acceptDeadline)) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_REQUEST_EXPIRED);
        }
    }

    /**
     * 이 회원이 볼 수 있는 요청서인지 확인한다. 호스트·게스트만 볼 수 있고, 보류가 끝나지 않았거나 실패한 요청서는 숨긴다.
     * 볼 수 없으면 요청서가 있다는 사실도 알리지 않도록 "없음"으로 응답한다.
     *
     * @throws AppearanceException {@code APPEARANCE_REQUEST_NOT_FOUND}
     */
    public void validateViewableBy(Long userId) {
        if (!status.isVisible() || !(isHost(userId) || isGuest(userId))) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_REQUEST_NOT_FOUND);
        }
    }

    public String getTitle() {
        return basicInfo.getTitle();
    }

    public String getHostChannelUrl() {
        return basicInfo.getHostChannelUrl();
    }

    public String getContent() {
        return basicInfo.getContent();
    }

    public boolean isHost(Long userId) {
        return hostId.equals(userId);
    }

    public boolean isGuest(Long userId) {
        return guestId.equals(userId);
    }

    private void requireStatus(AppearanceRequestStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("출연 요청서 상태가 %s가 아닙니다: %s".formatted(expected, status));
        }
    }
}
