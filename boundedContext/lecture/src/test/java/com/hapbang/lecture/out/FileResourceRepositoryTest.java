package com.hapbang.lecture.out;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.lecture.domain.FileResource;
import com.hapbang.lecture.domain.FileStatus;
import com.hapbang.lecture.domain.FileType;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class FileResourceRepositoryTest {

    @Autowired
    private FileResourceRepository fileResourceRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 저장한_파일을_업로드_상태와_함께_읽는다() {
        FileResource file = FileResource.create(SELLER_ID, FileType.LECTURE_VIDEO, "1강.mp4", "video/mp4", 1_024L, NOW);
        file.markUploaded(1_024L, NOW.plusMinutes(1));
        Long fileId = fileResourceRepository.save(file).getId();
        entityManager.flush();
        entityManager.clear();

        FileResource found = fileResourceRepository.findById(fileId).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(FileStatus.UPLOADED);
        assertThat(found.getObjectKey()).isEqualTo(file.getObjectKey());
        assertThat(found.getFileSize()).isEqualTo(1_024L);
        assertThat(found.getUploadedAt()).isEqualTo(NOW.plusMinutes(1));
    }

    @Test
    void 같은_object_key는_저장할_수_없다() {
        FileResource saved = fileResourceRepository.saveAndFlush(
                FileResource.create(SELLER_ID, FileType.LECTURE_VIDEO, "a.mp4", "video/mp4", 1_024L, NOW));
        FileResource duplicated = FileResource.create(OTHER_USER_ID, FileType.LECTURE_VIDEO, "b.mp4", "video/mp4",
                1_024L, NOW);
        ReflectionTestUtils.setField(duplicated, "objectKey", saved.getObjectKey());

        assertThatThrownBy(() -> fileResourceRepository.saveAndFlush(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
