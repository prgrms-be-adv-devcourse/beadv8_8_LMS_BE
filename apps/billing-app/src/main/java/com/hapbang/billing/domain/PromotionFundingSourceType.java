package com.hapbang.billing.domain;

// 프로모션 지갑 충전 HP가 어디서 왔는지 구분한다.
public enum PromotionFundingSourceType {
    /** 관리자 발행. 관리자가 근거 번호·메모를 붙여 새 HP를 발행해 프로모션 지갑에 넣는다. 이 HP는 판매자 지급·환전을 거쳐 현금이 된다 */
    ADMIN_GRANT
}
