package com.hapbang.appearance.domain;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스 간 HP 대사에서 어긋난 건 하나 (#581). 고치지 않고 남기기만 한다.
 * 업무 상태·지갑 상태는 관리자가 읽는 요약 문자열이다(원본은 각 모듈의 기록).
 */
@Entity
@Table(name = "hp_reconciliation_mismatches", schema = "appearances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HpReconciliationMismatch extends BaseEntity {

    /** 상태 요약 저장 최대 길이 (DB 컬럼과 같다) */
    static final int STATE_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long runId;

    /** 지갑 참조 유형 (요청서·모집글) */
    @Enumerated(EnumType.STRING)
    private HpTaskRefType referenceType;

    private Long referenceId;

    /** 지급 건처럼 출연 하나에 걸린 어긋남이면 그 출연 ID */
    private Long appearanceId;

    @Enumerated(EnumType.STRING)
    private HpReconciliationRule rule;

    private String appearanceState;

    private String walletState;

    public HpReconciliationMismatch(Long runId, HpTaskRefType referenceType, Long referenceId, Long appearanceId,
                                    HpReconciliationRule rule, String appearanceState, String walletState) {
        this.runId = runId;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.appearanceId = appearanceId;
        this.rule = rule;
        this.appearanceState = truncate(appearanceState);
        this.walletState = truncate(walletState);
    }

    private static String truncate(String state) {
        if (state.length() <= STATE_MAX) {
            return state;
        }
        return state.substring(0, STATE_MAX);
    }
}
