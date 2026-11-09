package com.hapbang.member.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.global.storage.PresignedUrl;
import com.hapbang.member.global.storage.S3FileStorage;
import com.hapbang.member.domain.ProfileImage;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.ProfileImageKey;
import com.hapbang.member.out.ProfileImageUrlCache;
import com.hapbang.member.out.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 프로필 사진: S3 저장(트랜잭션 밖)·key 변경(트랜잭션)·조회 URL(캐시) */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberProfileImageUseCase {

    /** 조회 URL 만료 (2026-10-13 결정) */
    static final Duration URL_EXPIRY = Duration.ofHours(1);

    /**
     * 사진 응답의 Cache-Control (#770). 같은 URL을 다시 받으면 브라우저가 S3에 묻지 않고 저장해 둔 사진을 쓴다.
     * 공용 캐시(프록시)에는 두지 않고({@code private}), 기간은 서명할 때 URL이 남은 유효 시간({@link #URL_EXPIRY})을 넘지 않는다.
     * 사진을 바꾸면 URL이 바뀌므로 예전 사진이 남아 보이지 않는다
     */
    static final String BROWSER_CACHE_CONTROL = "private, max-age=" + URL_EXPIRY.toSeconds();

    private final MemberSupport memberSupport;
    private final S3FileStorage s3FileStorage;
    private final ProfileImageUrlCache profileImageUrlCache;
    private final UserRepository userRepository;

    /** 검사 후 새 key로 S3에 올리고 key를 돌려준다. DB를 쓰지 않으므로 트랜잭션 밖에서 부른다 */
    public String store(Long userId, String contentType, long contentLength, InputStream body) throws IOException {
        ProfileImage.validate(contentType, contentLength);
        byte[] head = body.readNBytes(ProfileImage.SIGNATURE_LENGTH);
        ProfileImage.validateSignature(contentType, head);
        String key = ProfileImage.newKey(userId);
        // 읽은 앞부분과 남은 스트림을 이어 붙여 올린다 (전체를 메모리에 모으지 않는다)
        s3FileStorage.upload(key, ProfileImage.baseType(contentType), contentLength,
                new SequenceInputStream(new ByteArrayInputStream(head), body));
        return key;
    }

    // 다른 UseCase와 달리 여기에 트랜잭션을 둔다: Facade는 S3 업로드 때문에 트랜잭션 없이 이 메서드를 부른다
    /** 프로필 사진 key 변경 (null이면 연결 해제). 옛 파일은 커밋 뒤 {@link #eraseReplaced}로 비운다 */
    @Transactional
    public User change(Long userId, String key) {
        User user = memberSupport.findActiveUser(userId);
        user.changeProfileImage(key);
        // 역할 변경과 겹치면 409
        memberSupport.flush();
        return user;
    }

    /**
     * 바뀌거나 내린 옛 사진 파일을 빈 파일로 덮어 지운다 (#594). key 변경이 커밋된 뒤에 부른다(변경이 실패하면 옛 사진이 그대로
     * 쓰이므로). 지우지 못해도 사진 변경은 이미 끝났으므로 실패로 돌려주지 않고 로그만 남긴다. 남은 파일은 탈퇴 회원 파기 때 폴더째 지운다
     */
    public void eraseReplaced(String previousKey, String currentKey) {
        if (previousKey == null || previousKey.equals(currentKey)) {
            return;
        }
        try {
            s3FileStorage.erase(previousKey);
        } catch (RuntimeException e) {
            log.warn("바뀐 옛 프로필 사진을 지우지 못했습니다. key={}", previousKey, e);
        }
    }

    /**
     * 회원별 프로필 사진 조회 URL (#770 일괄 조회). 요청한 회원 ID마다 값이 있고(중복은 한 번), 사진이 없거나 없는 회원이거나
     * 프로필을 볼 수 없는 회원(정상이 아님, 프로필 조회와 같은 기준)이면 null이다. 회원은 쿼리 한 번, 캐시는 MGET 한 번으로 읽는다.
     * 트랜잭션을 두지 않는다(쿼리는 하나이고, Redis·서명하는 동안 DB 연결을 잡지 않게)
     */
    public Map<Long, String> urlsOfMembers(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        List<ProfileImageKey> keys = userRepository.findActiveProfileImageKeys(memberIds);
        Map<String, String> urls = urlsOf(keys.stream().map(ProfileImageKey::key).toList());
        Map<Long, String> result = new LinkedHashMap<>();
        memberIds.forEach(memberId -> result.put(memberId, null));
        keys.forEach(key -> result.put(key.userId(), urls.get(key.key())));
        return result;
    }

    /** 조회 URL (Presigned GET, 1시간). 사진이 없으면 null. 캐시를 거친다({@link #urlsOf}) */
    public String urlOf(String key) {
        return key == null ? null : urlsOf(List.of(key)).get(key);
    }

    /**
     * 여러 사진의 조회 URL (S3 key → URL, #770). 캐시에서 한 번에 읽고, 없는 것만 서명해 캐시에 넣는다.
     * 같은 사진은 캐시에 있는 동안 같은 URL이라 브라우저 캐시가 적중한다. 사진을 바꾸면 key가 새로 생겨 새 URL이 된다.
     * 동시에 같은 사진을 놓친 요청은 먼저 캐시에 넣은 주소를 받는다(없을 때만 저장). 캐시를 쓸 수 없으면(Redis 장애) 모두 새로 서명하고
     * 저장도 건너뛴다(읽기에 이어 저장까지 Redis 시간 제한을 두 번 기다리지 않게). null key는 건너뛴다
     */
    public Map<String, String> urlsOf(Collection<String> keys) {
        Set<String> distinctKeys = keys.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinctKeys.isEmpty()) {
            return Map.of();
        }
        ProfileImageUrlCache.Lookup cached = profileImageUrlCache.findAll(distinctKeys);
        Map<String, String> urls = new HashMap<>(cached.found());
        List<ProfileImageUrlCache.Entry> signed = new ArrayList<>();
        for (String key : distinctKeys) {
            if (urls.containsKey(key)) {
                continue;
            }
            PresignedUrl presigned = s3FileStorage.presignDownloadWithCacheControl(key, URL_EXPIRY, BROWSER_CACHE_CONTROL);
            urls.put(key, presigned.url());
            // 서명한 뒤의 시각으로 잰다(앞의 시각이면 자격 증명 남은 시간을 길게 잡는다)
            ProfileImageUrlCacheTtl.of(presigned, Instant.now())
                    .ifPresent(ttl -> signed.add(new ProfileImageUrlCache.Entry(key, presigned.url(), ttl)));
        }
        if (cached.available() && !signed.isEmpty()) {
            // 다른 요청이 먼저 넣은 주소가 있으면 그 주소로 바꿔 준다
            urls.putAll(profileImageUrlCache.putAllIfAbsent(signed));
        }
        return urls;
    }
}
