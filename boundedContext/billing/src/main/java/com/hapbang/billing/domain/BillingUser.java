package com.hapbang.billing.domain;

import com.hapbang.shared.member.domain.ReplicaUser;
import com.hapbang.shared.member.dto.UserDto;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

// Billing의 회원 복제본 (#294). 회원 모듈의 가입·변경 이벤트로만 만들고 갱신한다(회원 테이블과 FK·JOIN 없음).
// 갱신은 shared ReplicaUser.syncFrom(이미 반영한 회원 버전 이하의 정보는 무시)을 그대로 쓴다.
// 관리자 환전 승인 때 신청자가 정상 상태의 인플루언서인지 확인하는 데만 쓴다. 지갑 생성과는 무관하다.
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
}
