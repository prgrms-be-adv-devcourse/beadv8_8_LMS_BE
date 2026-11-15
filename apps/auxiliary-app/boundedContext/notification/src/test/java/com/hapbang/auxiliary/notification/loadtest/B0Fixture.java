package com.hapbang.auxiliary.notification.loadtest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 생성한 B0 묶음.
 *
 * @param members       회원 가입 레코드(먼저 넣는다)
 * @param alerts        알림 레코드(복제본 확인 뒤 넣는다), 파일 순서
 * @param eventTimes    업무 사건 시각(G4: 알림 시작 시각 이후)
 * @param scheduleTimes 예약의 보낼 시각·업무 기한(G4: 2027 이후)
 */
public record B0Fixture(List<B0Record> members, List<B0Record> alerts, B0Manifest manifest, List<Instant> eventTimes,
                        List<Instant> scheduleTimes) {

    public List<B0Record> allRecords() {
        List<B0Record> all = new ArrayList<>(members);
        all.addAll(alerts);
        return all;
    }

    /** 모든 줄의 SHA-256 */
    public String contentHash() {
        return B0Record.sha256(allRecords().stream().map(B0Record::line).collect(Collectors.joining("\n")));
    }
}
