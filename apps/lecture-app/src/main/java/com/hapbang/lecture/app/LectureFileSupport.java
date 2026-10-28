package com.hapbang.lecture.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.domain.FileResource;
import com.hapbang.lecture.domain.FileType;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.out.FileResourceRepository;

import lombok.RequiredArgsConstructor;

/** 강의 파일 조회와 강의에 연결할 파일 검증 (D21) */
@Service
@RequiredArgsConstructor
public class LectureFileSupport {

    private final FileResourceRepository fileResourceRepository;

    public FileResource findFile(Long fileId) {
        return fileResourceRepository.findById(fileId)
                .orElseThrow(() -> new LectureException(LectureErrorCode.FILE_NOT_FOUND));
    }

    /**
     * 썸네일(있으면)과 콘텐츠 파일이 모두 판매자 본인이 올렸고, 업로드가 끝났고, 용도가 맞는지 확인한다.
     * 하나라도 아니면 {@code INVALID_LECTURE_FILE}로 거부한다. 같은 파일을 여러 콘텐츠·강의에 연결해도 된다.
     */
    public void validateAttachable(Long sellerUserId, Long thumbnailFileId, List<LectureContentSpec> contentSpecs) {
        List<Attachment> attachments = new ArrayList<>();
        if (thumbnailFileId != null) {
            attachments.add(new Attachment(thumbnailFileId, FileType.LECTURE_THUMBNAIL));
        }
        contentSpecs.forEach(spec -> attachments.add(new Attachment(spec.fileId(), spec.contentType().fileType())));

        Map<Long, FileResource> files = fileResourceRepository
                .findAllById(attachments.stream().map(Attachment::fileId).distinct().toList()).stream()
                .collect(Collectors.toMap(FileResource::getId, Function.identity()));
        for (Attachment attachment : attachments) {
            FileResource file = files.get(attachment.fileId());
            if (file == null) {
                throw FileResource.notAttachable(attachment.fileId());
            }
            file.validateAttachableBy(sellerUserId, attachment.fileType());
        }
    }

    /** 강의 썸네일 하나만 확인한다(정보 수정). 없으면 확인하지 않는다 */
    public void validateThumbnail(Long sellerUserId, Long thumbnailFileId) {
        validateAttachable(sellerUserId, thumbnailFileId, List.of());
    }

    private record Attachment(Long fileId, FileType fileType) {
    }
}
