package com.hapbang.auxiliary.chatbot.domain;

import jakarta.persistence.Column;
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

/** 싱크 회차의 어긋난 항목 하나 (#716). 회차당 최대 N건, 종류마다 최근 M회만 남긴다 */
@Entity
@Table(name = "sync_mismatch", schema = "chatbot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SyncMismatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncType type;

    /** 원본의 ID(출연 ID, 리뷰 ID) */
    @Column(nullable = false)
    private Long sourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncMismatchKind kind;

    private Long ourVersion;

    private Long sourceVersion;

    private SyncMismatch(Long runId, SyncType type, Long sourceId, SyncMismatchKind kind, Long ourVersion,
            Long sourceVersion) {
        this.runId = runId;
        this.type = type;
        this.sourceId = sourceId;
        this.kind = kind;
        this.ourVersion = ourVersion;
        this.sourceVersion = sourceVersion;
    }

    /**
     * 회차에서 찾은 어긋남 하나를 만든다.
     *
     * @param ourVersion    반영 전 우리 version. 없거나 version 없는 데이터면 null
     * @param sourceVersion 원본 version. 원본에 없거나 version 없는 데이터면 null
     */
    public static SyncMismatch create(Long runId, SyncType type, Long sourceId, SyncMismatchKind kind, Long ourVersion,
            Long sourceVersion) {
        return new SyncMismatch(runId, type, sourceId, kind, ourVersion, sourceVersion);
    }
}
