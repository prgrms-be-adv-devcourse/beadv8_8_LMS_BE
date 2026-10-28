package com.hapbang.lecture.in;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.lecture.global.storage.S3FileStorage;
import com.hapbang.lecture.domain.FileResource;
import com.hapbang.lecture.domain.FileStatus;
import com.hapbang.lecture.out.FileResourceRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.S3MockTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;

import com.jayway.jsonpath.JsonPath;

// 파일 정보 등록 → 본문 업로드(서버가 S3Mock에 저장) → 강의 연결을 HTTP부터 S3까지 관통해 검증한다. 정책 값의 세부 규칙은 FileResourceTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
@Import(S3MockTestcontainersConfig.class)
class LectureFileApiTest {

    private static final Long SELLER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final byte[] VIDEO = {1, 2, 3, 4, 5};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FileResourceRepository fileResourceRepository;

    @Autowired
    private S3FileStorage s3FileStorage;

    @Test
    void 인증된_인플루언서가_파일_정보를_등록하면_fileId만_받고_파일은_PENDING으로_남는다() throws Exception {
        String response = registerFile(SELLER_ID, Role.VERIFIED_INFLUENCER, "video/mp4", VIDEO.length)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.data.fileId").isNumber())
                .andExpect(jsonPath("$.data.uploadUrl").doesNotExist())
                .andExpect(jsonPath("$.data.expiresAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        FileResource file = fileResourceRepository.findById(fileId(response)).orElseThrow();
        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(file.getUploaderUserId()).isEqualTo(SELLER_ID);
    }

    @Test
    void 인증된_인플루언서가_아니면_파일_정보를_등록할_수_없다() throws Exception {
        registerFile(OTHER_USER_ID, Role.INFLUENCER, "video/mp4", VIDEO.length)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 허용하지_않는_형식이면_400_UNSUPPORTED_FILE_CONTENT_TYPE() throws Exception {
        registerFile(SELLER_ID, Role.VERIFIED_INFLUENCER, "video/webm", VIDEO.length)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_CONTENT_TYPE"));
    }

    @Test
    void 파일_크기가_없으면_400_INVALID_REQUEST() throws Exception {
        mockMvc.perform(post("/api/v1/lectures/files")
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileType": "LECTURE_VIDEO", "originalFilename": "1강.mp4", "contentType": "video/mp4"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("fileSize"));
    }

    @Test
    void 업로더가_본문을_올리면_204이고_S3에_저장되며_UPLOADED가_된다() throws Exception {
        Long fileId = registerVideo(VIDEO.length);

        uploadContent(SELLER_ID, fileId, "video/mp4", VIDEO).andExpect(status().isNoContent());

        FileResource file = fileResourceRepository.findById(fileId).orElseThrow();
        assertThat(file.getStatus()).isEqualTo(FileStatus.UPLOADED);
        assertThat(file.getUploadedAt()).isNotNull();
        assertThat(s3FileStorage.find(file.getObjectKey())).hasValueSatisfying(stored -> {
            assertThat(stored.size()).isEqualTo(VIDEO.length);
            assertThat(stored.contentType()).isEqualTo("video/mp4");
        });
    }

    @Test
    void 다른_회원이_올리면_403_NOT_FILE_UPLOADER이고_S3에_저장되지_않는다() throws Exception {
        Long fileId = registerVideo(VIDEO.length);

        uploadContent(OTHER_USER_ID, fileId, "video/mp4", VIDEO)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_FILE_UPLOADER"));

        assertPendingAndNotStored(fileId);
    }

    @Test
    void 이미_올린_파일에_다시_올리면_409_FILE_ALREADY_UPLOADED() throws Exception {
        Long fileId = registerVideo(VIDEO.length);
        uploadContent(SELLER_ID, fileId, "video/mp4", VIDEO).andExpect(status().isNoContent());

        uploadContent(SELLER_ID, fileId, "video/mp4", VIDEO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_ALREADY_UPLOADED"));
    }

    @Test
    void 본문_길이를_모르면_411_FILE_LENGTH_REQUIRED() throws Exception {
        Long fileId = registerVideo(VIDEO.length);

        mockMvc.perform(put("/api/v1/lectures/files/{fileId}/content", fileId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType("video/mp4"))
                .andExpect(status().isLengthRequired())
                .andExpect(jsonPath("$.code").value("FILE_LENGTH_REQUIRED"));

        assertPendingAndNotStored(fileId);
    }

    @Test
    void 등록_크기와_다른_본문이면_400_FILE_SIZE_MISMATCH이고_PENDING으로_남는다() throws Exception {
        Long fileId = registerVideo(VIDEO.length + 1);

        uploadContent(SELLER_ID, fileId, "video/mp4", VIDEO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_SIZE_MISMATCH"));

        assertPendingAndNotStored(fileId);
    }

    @Test
    void 등록_형식과_다른_Content_Type이면_400_FILE_CONTENT_TYPE_MISMATCH() throws Exception {
        Long fileId = registerVideo(VIDEO.length);

        uploadContent(SELLER_ID, fileId, "application/octet-stream", VIDEO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_CONTENT_TYPE_MISMATCH"));

        assertPendingAndNotStored(fileId);
    }

    @Test
    void 없는_파일에_올리면_404_FILE_NOT_FOUND() throws Exception {
        uploadContent(SELLER_ID, 999_999L, "video/mp4", VIDEO)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    }

    @Test
    void 올린_파일로_강의를_등록할_수_있다() throws Exception {
        Long fileId = registerVideo(VIDEO.length);
        uploadContent(SELLER_ID, fileId, "video/mp4", VIDEO).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/lectures")
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "방송 장비 세팅 강의",
                                  "priceHp": 10000,
                                  "contents": [{"fileId": %d, "title": "1강", "contentType": "VIDEO"}]
                                }
                                """.formatted(fileId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201));
    }

    private ResultActions registerFile(Long userId, Role role, String contentType, long fileSize)
            throws Exception {
        return mockMvc.perform(post("/api/v1/lectures/files")
                .with(TestLogin.as(userId, role)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fileType": "LECTURE_VIDEO", "originalFilename": "1강 장비 고르기.mp4",
                         "contentType": "%s", "fileSize": %d}
                        """.formatted(contentType, fileSize)));
    }

    private Long registerVideo(long fileSize) throws Exception {
        String response = registerFile(SELLER_ID, Role.VERIFIED_INFLUENCER, "video/mp4", fileSize)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andReturn().getResponse().getContentAsString();
        return fileId(response);
    }

    /** 브라우저처럼 파일 본문을 그대로 PUT 한다 */
    private ResultActions uploadContent(Long userId, Long fileId, String contentType, byte[] body) throws Exception {
        return mockMvc.perform(put("/api/v1/lectures/files/{fileId}/content", fileId)
                .with(TestLogin.as(userId, Role.VERIFIED_INFLUENCER)).with(csrf())
                .contentType(contentType)
                .content(body));
    }

    private void assertPendingAndNotStored(Long fileId) {
        FileResource file = fileResourceRepository.findById(fileId).orElseThrow();
        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(s3FileStorage.find(file.getObjectKey())).isEmpty();
    }

    private static Long fileId(String response) {
        return ((Number) JsonPath.read(response, "$.data.fileId")).longValue();
    }
}
