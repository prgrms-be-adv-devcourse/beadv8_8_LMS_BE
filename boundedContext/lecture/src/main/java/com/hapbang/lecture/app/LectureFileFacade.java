package com.hapbang.lecture.app;

import java.io.InputStream;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.FileUploadResponse;
import com.hapbang.lecture.domain.FileType;

import lombok.RequiredArgsConstructor;

/**
 * 강의 파일 업로드 진입점 (설계 8.3). 파일 정보는 인증된 인플루언서만 등록한다 (D16).
 * 본문 업로드는 역할 대신 업로더 본인인지를 FileResource가 검사한다.
 */
@Service
@RequiredArgsConstructor
public class LectureFileFacade {

    private final FileUploadUseCase fileUploadUseCase;
    private final FileContentUploadUseCase fileContentUploadUseCase;

    /** 파일 정보 등록 */
    @PreAuthorize("hasRole('VERIFIED_INFLUENCER')")
    @Transactional
    public FileUploadResponse register(Long userId, FileType fileType, String originalFilename,
                                       String contentType, long fileSize) {
        return fileUploadUseCase.register(userId, fileType, originalFilename, contentType, fileSize);
    }

    /** 파일 본문 업로드. 본문을 DB 트랜잭션 밖에서 S3로 보내므로 @Transactional을 걸지 않는다(D25) */
    public void upload(Long userId, Long fileId, String contentType, long contentLength, InputStream body) {
        fileContentUploadUseCase.upload(userId, fileId, contentType, contentLength, body);
    }
}
