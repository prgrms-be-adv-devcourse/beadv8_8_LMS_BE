package com.hapbang.lecture.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.FileUploadResponse;
import com.hapbang.lecture.domain.FileType;

import lombok.RequiredArgsConstructor;

/**
 * 강의 파일 업로드 진입점 (설계 8.3). 파일 정보는 인증된 인플루언서만 등록한다 (D16).
 * 업로드 완료는 역할 대신 업로더 본인인지를 FileResource가 검사한다.
 */
@Service
@RequiredArgsConstructor
public class LectureFileFacade {

    private final FileUploadUseCase fileUploadUseCase;
    private final FileCompleteUseCase fileCompleteUseCase;

    /** 파일 정보 등록 */
    @PreAuthorize("hasRole('VERIFIED_INFLUENCER')")
    @Transactional
    public FileUploadResponse register(Long userId, FileType fileType, String originalFilename,
                                       String contentType, long fileSize) {
        return fileUploadUseCase.register(userId, fileType, originalFilename, contentType, fileSize);
    }

    /** 업로드 완료 */
    @Transactional
    public void complete(Long userId, Long fileId) {
        fileCompleteUseCase.complete(userId, fileId);
    }
}
