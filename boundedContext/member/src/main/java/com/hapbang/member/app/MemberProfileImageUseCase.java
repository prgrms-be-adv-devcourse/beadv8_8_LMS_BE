package com.hapbang.member.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.global.storage.S3FileStorage;
import com.hapbang.member.domain.ProfileImage;
import com.hapbang.member.domain.User;

import lombok.RequiredArgsConstructor;

/** 프로필 사진: S3 저장(트랜잭션 밖)·key 변경(트랜잭션)·조회 URL */
@Service
@RequiredArgsConstructor
public class MemberProfileImageUseCase {

    /** 조회 URL 만료 (2026-10-13 결정) */
    static final Duration URL_EXPIRY = Duration.ofHours(1);

    private final MemberSupport memberSupport;
    private final S3FileStorage s3FileStorage;

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
    /** 프로필 사진 key 변경 (null이면 연결 해제. S3 파일은 지우지 않는다) */
    @Transactional
    public User change(Long userId, String key) {
        User user = memberSupport.findActiveUser(userId);
        user.changeProfileImage(key);
        // 역할 변경과 겹치면 409
        memberSupport.flush();
        return user;
    }

    /** 조회 URL (Presigned GET, 1시간). 사진이 없으면 null */
    public String urlOf(String key) {
        return key == null ? null : s3FileStorage.presignDownload(key, URL_EXPIRY).url();
    }
}
