package com.hapbang.member.out;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 프로필 사진 조회 주소(Presigned GET) 캐시 (#770). S3 key마다 서명한 주소를 잠시 두어, 같은 사진은 같은 주소로 나가게 한다
 * (브라우저 캐시 적중, S3 전송 감소).
 * <p>
 * 사진을 바꾸면 S3 key가 새로 생기므로 지울 일이 없다. 캐시를 쓸 수 없으면(Redis 장애) 예외를 던지지 않고 비어 있는 것처럼 동작해,
 * 부르는 쪽은 매번 서명한다.
 */
public interface ProfileImageUrlCache {

    /** 캐시에 있는 주소 (S3 key → 주소). 없는 key와 캐시를 쓸 수 없을 때는 빠진다 */
    Map<String, String> findAll(Collection<String> keys);

    /** 주소를 저장한다. 저장하지 못해도 예외를 던지지 않는다 */
    void putAll(List<Entry> entries);

    /**
     * @param key S3 key (모듈 기준, 예: member/profile/3/uuid)
     * @param url 서명한 주소. 로그에 남기지 않는다
     * @param ttl 캐시 유지 시간 (1초 이상)
     */
    record Entry(String key, String url, Duration ttl) {
    }
}
