package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.global.storage.S3FileStorage;
import com.hapbang.lecture.domain.FileType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.out.FileResourceRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

import software.amazon.awssdk.core.exception.SdkClientException;

// 썸네일 URL 서명이 실패할 때의 응답 (D26). S3 경계(S3FileStorage)만 가짜로 바꾼다.
@IntegrationTest
@AutoConfigureMockMvc
class LectureThumbnailFailureApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FileResourceRepository fileResourceRepository;

    @MockitoBean
    private S3FileStorage s3FileStorage;

    @Test
    void 썸네일_서명이_AWS_오류로_실패하면_목록은_200이고_첫_실패_뒤로는_서명하지_않는다() throws Exception {
        given(s3FileStorage.presignDownload(any(), any()))
                .willThrow(SdkClientException.create("Unable to load credentials"));
        saveOnSaleLecture();
        saveOnSaleLecture();

        mockMvc.perform(get("/api/v1/lectures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].thumbnailUrl").value(nullValue()))
                .andExpect(jsonPath("$.content[1].thumbnailUrl").value(nullValue()));
        verify(s3FileStorage, times(1)).presignDownload(any(), any());
    }

    @Test
    void 썸네일_서명이_AWS_오류로_실패해도_상세는_200이고_null이다() throws Exception {
        given(s3FileStorage.presignDownload(any(), any()))
                .willThrow(SdkClientException.create("Unable to load credentials"));
        Long lectureId = saveOnSaleLecture();

        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lectureId").value(lectureId))
                .andExpect(jsonPath("$.thumbnailUrl").value(nullValue()));
    }

    @Test
    void AWS_오류가_아닌_서명_예외는_500_INTERNAL_ERROR다() throws Exception {
        given(s3FileStorage.presignDownload(any(), any()))
                .willThrow(new IllegalArgumentException("key는 비어 있거나 '/'로 시작할 수 없습니다"));
        saveOnSaleLecture();

        mockMvc.perform(get("/api/v1/lectures"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    private Long saveOnSaleLecture() {
        Long thumbnailId = fileResourceRepository.save(uploadedFile(SELLER_ID, FileType.LECTURE_THUMBNAIL)).getId();
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, thumbnailId, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture).getId();
    }
}
