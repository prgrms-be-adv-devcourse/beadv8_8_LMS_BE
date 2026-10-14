package com.hapbang.appearance.domain;

import java.time.LocalDateTime;

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
 * 출연 모집글. 호스트가 여러 게스트를 공개 모집하는 글이다 (1:N).
 * <ul>
 *     <li>올릴 때 1인당 출연료 × 모집 인원만큼 HP를 보류한다. 저장 직후는 {@code HOLD_PENDING}이고, 보류 결과에 따라
 *     {@code RECRUITING} 또는 {@code HOLD_FAILED}가 된다.</li>
 *     <li>올린 뒤 수정할 수 있는 것은 제목·콘텐츠 설명·호스트 채널 URL뿐이며, 모집 중이고 마감 일시 전까지만 가능하다.</li>
 *     <li>기본 정보(제목·콘텐츠 설명·호스트 채널 URL)는 출연 요청서와 같은 값 객체({@link AppearanceBasicInfo})로 두고,
 *     형식 제한도 요청서와 같은 {@link AppearanceContentRules}를 쓴다.</li>
 * </ul>
 */
@Entity
@Table(name = "recruits", schema = "appearances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppearanceRecruit extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 모집글을 올린 호스트 회원 ID */
    private Long hostId;

    /** 기본 정보(제목·호스트 채널 URL·콘텐츠 설명). 등록 후 수정할 수 있는 값 */
    @Embedded
    private AppearanceBasicInfo basicInfo;

    /** 방송 예정 일시 */
    private LocalDateTime scheduledAt;

    /** 예상 출연 시간(분). 1 ~ 1,440 */
    private int expectedMinutes;


    /** 1인당 출연료 (HP). 최저 출연료({@link AppearancePolicy#minimumFee()}) 이상 */
    private long feePerPerson;

    /** 모집 인원. 1명 ~ 최대 모집 인원({@link AppearancePolicy#maxRecruitCount()}) */
    private int recruitCount;

    /** 현재 성립 인원 (지원 수락 처리에서 늘어난다) */
    private int selectedCount;

    /** 모집 마감 일시. 현재 이후이고 방송 예정 일시보다 앞서야 한다 */
    private LocalDateTime recruitDeadline;

    @Enumerated(EnumType.STRING)
    private AppearanceRecruitStatus status;

    /** 마감 방식 (모집 마감 처리에서 사용) */
    @Enumerated(EnumType.STRING)
    private AppearanceRecruitCloseType closeType;

    /** 마감 시각 (모집 마감 처리에서 사용) */
    private LocalDateTime closedAt;

    /** 모집 전체 취소 시각 (모집 취소 처리에서 사용) */
    private LocalDateTime canceledAt;

    /** 낙관적 락. 동시에 수정·지원 수락할 때 나중 것을 막는다 */
    @Version
    private Long version;

    private AppearanceRecruit(Long hostId, AppearanceBasicInfo basicInfo, AppearanceRecruitContent recruitContent) {
        this.hostId = hostId;
        this.basicInfo = basicInfo;
        this.scheduledAt = recruitContent.scheduledAt();
        this.expectedMinutes = recruitContent.expectedMinutes();
        this.feePerPerson = recruitContent.feePerPerson();
        this.recruitCount = recruitContent.recruitCount();
        this.selectedCount = 0;
        this.recruitDeadline = recruitContent.recruitDeadline();
        this.status = AppearanceRecruitStatus.HOLD_PENDING;
    }

    /**
     * 출연 모집글을 만든다. HP 보류 전이라 {@code HOLD_PENDING} 상태다.
     *
     * @throws AppearanceException 입력 값 형식 오류({@code INVALID_REQUEST}), 최저 출연료 미달({@code FEE_BELOW_MINIMUM}),
     *                             모집 인원 범위 밖({@code RECRUIT_COUNT_OUT_OF_RANGE}), 기한 순서 오류({@code INVALID_RECRUIT_SCHEDULE})
     */
    public static AppearanceRecruit create(Long hostId, AppearanceRecruitContent recruitContent,
                                           AppearancePolicy policy, LocalDateTime now) {
        AppearanceBasicInfo basicInfo = AppearanceBasicInfo.of(recruitContent.title(), recruitContent.hostChannelUrl(),
                recruitContent.content());
        AppearanceContentRules.validateExpectedMinutes(recruitContent.expectedMinutes());
        if (policy.isBelowMinimumFee(recruitContent.feePerPerson())) {
            throw new AppearanceException(AppearanceErrorCode.FEE_BELOW_MINIMUM,
                    "1인당 출연료는 최저 출연료(%,d HP) 이상이어야 합니다.".formatted(policy.minimumFee()));
        }
        if (!policy.isRecruitCountAllowed(recruitContent.recruitCount())) {
            throw new AppearanceException(AppearanceErrorCode.RECRUIT_COUNT_OUT_OF_RANGE,
                    "모집 인원은 %d명 이상 %d명 이하여야 합니다.".formatted(AppearancePolicy.MIN_RECRUIT_COUNT,
                            policy.maxRecruitCount()));
        }
        // 현재 < 모집 마감 일시 < 방송 예정 일시
        if (!recruitContent.recruitDeadline().isAfter(now)
                || !recruitContent.recruitDeadline().isBefore(recruitContent.scheduledAt())) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_RECRUIT_SCHEDULE);
        }
        AppearanceRecruit recruit = new AppearanceRecruit(hostId, basicInfo, recruitContent);
        // 보류할 금액을 계산할 수 있는지 저장 전에 확인한다
        recruit.totalFee();
        return recruit;
    }

    /**
     * 보류할 금액 = 1인당 출연료 × 모집 인원.
     *
     * @throws AppearanceException 금액이 너무 커서 계산할 수 없을 때({@code INVALID_REQUEST})
     */
    public long totalFee() {
        try {
            return Math.multiplyExact(feePerPerson, recruitCount);
        } catch (ArithmeticException exception) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST, "1인당 출연료가 너무 큽니다.");
        }
    }

    /** HP 보류 성공 → 모집 중. 이미 반영됐으면 그대로 둔다 (올리기 요청과 보류 재확인 배치가 함께 반영해도 안전하도록) */
    public void markRecruiting() {
        if (status == AppearanceRecruitStatus.RECRUITING) {
            return;
        }
        requireStatus(AppearanceRecruitStatus.HOLD_PENDING);
        this.status = AppearanceRecruitStatus.RECRUITING;
    }

    /** Billing이 HP 보류를 거절함 (잔액 부족 등). 이미 반영됐으면 그대로 둔다 */
    public void markHoldFailed() {
        if (status == AppearanceRecruitStatus.HOLD_FAILED) {
            return;
        }
        requireStatus(AppearanceRecruitStatus.HOLD_PENDING);
        this.status = AppearanceRecruitStatus.HOLD_FAILED;
    }

    /**
     * 기본 정보(제목·콘텐츠 설명·호스트 채널 URL)를 수정한다. null인 값은 바꾸지 않는다.
     * 보낸 값으로 덮어쓰기만 하므로 같은 수정을 여러 번 해도 결과가 같다(멱등).
     * <p>
     * 모집 마감 일시는 DB 상태가 아니라 현재 시각으로 확인한다. 마감 처리가 아직 안 됐어도 마감 일시가 지나면 막는다.
     *
     * @throws AppearanceException 호스트가 아님({@code NOT_RECRUIT_HOST}), 모집 중이 아니거나 마감 일시가 지남
     *                             ({@code APPEARANCE_RECRUIT_NOT_EDITABLE}), 입력 값 형식 오류({@code INVALID_REQUEST})
     */
    public void updateBasicInfo(Long userId, String title, String content, String hostChannelUrl,
                                LocalDateTime now) {
        if (!isHost(userId)) {
            throw new AppearanceException(AppearanceErrorCode.NOT_RECRUIT_HOST);
        }
        if (status != AppearanceRecruitStatus.RECRUITING || !now.isBefore(recruitDeadline)) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_EDITABLE);
        }
        this.basicInfo = basicInfo.withChanges(title, content, hostChannelUrl);
    }

    /**
     * 공개된 모집글인지 확인한다. 보류가 끝나지 않았거나 실패한 모집글은 올린 호스트에게도 숨긴다.
     * 볼 수 없으면 모집글이 있다는 사실도 알리지 않도록 "없음"으로 응답한다.
     *
     * @throws AppearanceException {@code APPEARANCE_RECRUIT_NOT_FOUND}
     */
    public void validateVisible() {
        if (!status.isVisible()) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_FOUND);
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

    private void requireStatus(AppearanceRecruitStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("출연 모집글 상태가 %s가 아닙니다: %s".formatted(expected, status));
        }
    }
}
