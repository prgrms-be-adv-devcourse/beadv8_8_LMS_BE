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
 * 쿠폰 하나의 발급 저장소 용량 예약. 발급을 열 때(Redis 준비 전) 쿠폰 총수량만큼 만들고, 대사 배치가 그 쿠폰의 Redis 기록을 모두 정리한 뒤
 * 지운다. 행이 있는 쿠폰이 곧 "Redis 기록이 남았을 수 있는 쿠폰"이라 대사 배치의 대상 목록이다.
 * 행은 조건부 INSERT·DELETE({@code IssueCapacityReservationRepository})로만 만들고 지운다.
 */
@Entity
@Table(name = "issue_capacity_reservation", schema = "coupon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueCapacityReservation {

    @Id
    private Long couponId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private Instant reservedAt;
}
