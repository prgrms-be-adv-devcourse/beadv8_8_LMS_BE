package com.hapbang.member.app;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.hapbang.member.global.storage.PresignedUrl;

/**
 * 프로필 사진 조회 주소를 캐시에 두는 시간 (#770). {@link #MAX}와 "서명에 쓴 자격 증명이 끝나기까지 남은 시간 − {@link #CREDENTIALS_MARGIN}"
 * 중 짧은 쪽이다. 어느 쪽이든 캐시에서 꺼낸 주소는 받은 뒤 10분 이상 쓸 수 있다.
 * <p>
 * 자격 증명이 10분 이하 남아 저장하지 않은 주소(빈 값)는 바로 응답으로 나가고, 자격 증명과 함께 끝나 10분을 못 채울 수 있다.
 * EC2가 새 자격 증명을 만료 전에 미리 준비하므로 드물다. 그래서 API 설명은 "대개 10분 이상, 불러오지 못하면 다시 조회"다 (#770 리뷰).
 * <ul>
 *     <li>주소 유효 시간(1시간)보다 짧게 두어, 캐시에서 꺼낸 주소도 받은 뒤 10분 이상 쓸 수 있게 한다.</li>
 *     <li>서버는 인스턴스 역할의 임시 자격 증명으로 서명하고, 그 자격 증명이 끝나면 주소도 유효 시간 전에 끝난다(docs/deploy/s3.md).
 *     그래서 자격 증명이 끝나기 {@link #CREDENTIALS_MARGIN} 전에는 캐시에서 빠지게 한다(주소 유효 시간과 같은 10분 여유).</li>
 *     <li>Redis TTL이 초 단위라 초 아래는 버린다. 1초가 안 되면 저장하지 않는다(빈 값).</li>
 * </ul>
 */
final class ProfileImageUrlCacheTtl {

    /** 가장 긴 유지 시간. 주소 유효 시간 1시간보다 10분 짧다 */
    static final Duration MAX = Duration.ofMinutes(50);

    /** 캐시에서 꺼낸 주소를 자격 증명이 끝나기 전에 쓸 수 있게 남기는 시간. MAX가 주소 유효 시간에 남기는 여유와 같다 (#770 리뷰) */
    static final Duration CREDENTIALS_MARGIN = Duration.ofMinutes(10);

    private ProfileImageUrlCacheTtl() {
    }

    /** 주소를 캐시에 둘 시간. 저장하지 않아야 하면(자격 증명이 곧 끝남) 빈 값 */
    static Optional<Duration> of(PresignedUrl url, Instant now) {
        Duration ttl = MAX;
        if (url.credentialsExpiresAt() != null) {
            Duration usable = Duration.between(now, url.credentialsExpiresAt()).minus(CREDENTIALS_MARGIN);
            if (usable.compareTo(ttl) < 0) {
                ttl = usable;
            }
        }
        Duration seconds = Duration.ofSeconds(ttl.getSeconds());
        return seconds.isNegative() || seconds.isZero() ? Optional.empty() : Optional.of(seconds);
    }
}
