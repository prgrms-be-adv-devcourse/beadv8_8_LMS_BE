package com.hapbang.appearance.domain;

import java.time.LocalDateTime;

import com.hapbang.shared.jpa.BaseEntity;

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
 * 출연. 출연 성립으로 생긴 거래 1건이다 (게스트 1명 기준). 출연 취소 요청·확인·위약금·지급은 이 단위로 처리한다.
 * <p>
 * 출연료와 방송 예정 일시는 성립 시점 값을 그대로 둔다(스냅샷). 출연은 요청서와 모집 양쪽에서 생기므로,
 * 지급·위약금·반환을 출처와 관계없이 이 값으로 계산하기 위해서다.
 */
@Entity
@Table(name = "appearances", schema = "appearances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Appearance extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private AppearanceSourceType sourceType;

    /** 출처 출연 요청서 (요청에서 성립한 경우) */
    private Long requestId;

    /** 출처 모집 지원 (모집에서 성립한 경우, 모집 처리에서 사용) */
    private Long applicationId;

    /** 모집글 (모집별 조회용, 모집 처리에서 사용) */
    private Long recruitId;

    private Long hostId;

    private Long guestId;

    /** 출연료 스냅샷 (HP). 지급·위약금·반환의 기준 금액 */
    private long fee;

    /** 방송 예정 일시 스냅샷. 출연 확인 기한(D+7)·출연 취소 요청 마감의 기준 */
    private LocalDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    private AppearanceStatus status;

    /** 성립 시각 */
    private LocalDateTime establishedAt;

    // 아래는 출연 확인·취소 처리에서 채운다
    private String confirmType;

    private Long penaltyAmount;

    private LocalDateTime confirmedAt;

    private LocalDateTime noShowAt;

    private LocalDateTime disputedAt;

    private LocalDateTime canceledAt;

    /** 낙관적 락 */
    @Version
    private Long version;

    private Appearance(AppearanceRequest request, LocalDateTime now) {
        this.sourceType = AppearanceSourceType.REQUEST;
        this.requestId = request.getId();
        this.hostId = request.getHostId();
        this.guestId = request.getGuestId();
        this.fee = request.getFee();
        this.scheduledAt = request.getScheduledAt();
        this.status = AppearanceStatus.ESTABLISHED;
        this.establishedAt = now;
    }

    /**
     * 수락된 출연 요청서로 출연을 만든다 (출연 성립). HP는 이동하지 않고 요청서의 보류를 그대로 쓴다.
     */
    public static Appearance establishFrom(AppearanceRequest request, LocalDateTime now) {
        if (request.getStatus() != AppearanceRequestStatus.ACCEPTED) {
            throw new IllegalStateException("수락된 출연 요청서로만 출연을 만들 수 있습니다: " + request.getStatus());
        }
        return new Appearance(request, now);
    }
}
