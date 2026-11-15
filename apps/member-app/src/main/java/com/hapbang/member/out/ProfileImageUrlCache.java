package com.hapbang.member.out;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 프로필 사진 조회 주소(Presigned GET) 캐시 (#770). S3 key마다 서명한 주소를 잠시 두어, 같은 사진은 같은 주소로 나가게 한다
 * (브라우저 캐시 적중, S3 전송 감소).
 * <p>
 * 사진을 바꾸면 S3 key가 새로 생기므로 지울 일이 없다. 캐시를 쓸 수 없으면(Redis 장애·회로 열림·앱 종료 중) 예외를 던지지 않고
 * 비어 있는 것처럼 동작해, 부르는 쪽은 매번 서명한다.
 */
public interface ProfileImageUrlCache {

    /** 캐시에 있는 주소. 캐시를 쓸 수 없었으면 {@link Lookup#available()}이 false이고, 부르는 쪽은 저장도 건너뛴다 */
    Lookup findAll(Collection<String> keys);

    /**
     * key에 주소가 없을 때만 저장한다(SET NX). 동시에 같은 사진을 놓친 요청이 먼저 넣었으면 그 주소를 그대로 둔다.
     *
     * @return key마다 지금 캐시에 있는 주소(먼저 있던 주소 또는 이번에 넣은 주소). 저장하지 못했으면 빠진다. 예외를 던지지 않는다
     */
    Map<String, String> putAllIfAbsent(List<Entry> entries);

    /**
     * @param found     캐시에 있던 주소 (S3 key → 주소)
     * @param available 캐시를 쓸 수 있었는지. false면 Redis를 읽지 못한 것이다
     */
    record Lookup(Map<String, String> found, boolean available) {

        public static Lookup unavailable() {
            return new Lookup(Map.of(), false);
        }
    }

    /**
     * @param key S3 key (모듈 기준, 예: member/profile/3/uuid)
     * @param url 서명한 주소. 로그에 남기지 않는다
     * @param ttl 캐시 유지 시간 (1초 이상)
     */
    record Entry(String key, String url, Duration ttl) {
    }
}
