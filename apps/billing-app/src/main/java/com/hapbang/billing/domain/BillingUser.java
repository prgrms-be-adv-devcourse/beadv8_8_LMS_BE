package com.hapbang.billing.domain;

import com.hapbang.shared.member.domain.ReplicaUser;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.dto.UserDto;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

// Billing의 회원 복제본 (#294). 회원 모듈의 가입·변경 이벤트로만 만들고 갱신한다(회원 테이블과 FK·JOIN 없음).
// 갱신은 shared ReplicaUser.syncFrom(이미 반영한 회원 버전 이하의 정보는 무시)을 그대로 쓴다.
// 지갑 회원(용어집). HP 충전·환불·환전 신청 때 로그인한 역할과 함께 회원 상태·역할을 확인하고 (#537, BD-131),
// 관리자 환전 승인 때 신청자가 정상 상태의 인플루언서인지 확인한다. 지갑 생성과는 무관하다.
@Entity
@Table(name = "billing_user", schema = "billing")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingUser extends ReplicaUser {

    private BillingUser(UserDto user) {
        super(user);
    }

    // 회원 정보로 복제본을 만든다(회원 버전도 함께 기록).
    public static BillingUser from(UserDto user) {
        return new BillingUser(user);
    }

    // 환전 승인을 받을 수 있는 회원인지: 정상(ACTIVE) 상태의 인플루언서·인증된 인플루언서
    public boolean canReceiveExchangeApproval() {
        return isActive() && isInfluencerOrAbove();
    }

    // HP 충전(주문·승인)을 할 수 있는 회원인지: 정상(ACTIVE) 상태의 유저·인플루언서·인증된 인플루언서 (#537)
    public boolean canCharge() {
        return isActive() && usesHp();
    }

    // 충전 HP 환불을 신청할 수 있는 회원인지: 정상·정지 상태의 유저·인플루언서·인증된 인플루언서 (#537).
    // 환불은 회원이 결제한 돈을 돌려받는 것이라 정지를 이유로 막지 않는다. 탈퇴 회원은 막는다.
    public boolean canRefund() {
        return !isWithdrawn() && usesHp();
    }

    // 수익 HP 환전을 신청할 수 있는 회원인지: 정상(ACTIVE) 상태의 인플루언서·인증된 인플루언서 (#537). 승인 조건과 같다
    public boolean canRequestExchange() {
        return canReceiveExchangeApproval();
    }

    // 관리자는 HP를 쓰지 않는다 (BD-3)
    private boolean usesHp() {
        return getRole() != Role.ADMIN;
    }
}
