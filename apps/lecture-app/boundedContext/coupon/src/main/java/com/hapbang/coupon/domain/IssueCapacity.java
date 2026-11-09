package com.hapbang.coupon.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 발급 저장소 용량 예약 합계(행 하나). Redis 기록이 남은 쿠폰의 수량 합이다.
 * 값은 조건부 UPSERT·UPDATE({@code IssueCapacityRepository})로만 바꾸고 엔티티로 쓰지 않는다. 행이 없으면 합계 0이다.
 */
@Entity
@Table(name = "issue_capacity", schema = "coupon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueCapacity {

    /** 합계 행의 ID. 행은 이것 하나뿐이다 */
    public static final short SINGLE_ROW_ID = 1;

    @Id
    private Short id;

    @Column(nullable = false)
    private long reservedTotal;

    @Column(nullable = false)
    private Instant updatedAt;
}
