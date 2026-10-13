package com.hapbang.lecture.in;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.lecture.domain.FileResource;
import com.hapbang.lecture.domain.FileStatus;
import com.hapbang.lecture.out.FileResourceRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.S3MockTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;

import com.jayway.jsonpath.JsonPath;

// 업로드 URL 발급 → S3(S3Mock)에 PUT → 완료 확인을 HTTP부터 S3까지 관통해 검증한다. 정책 값의 세부 규칙은 FileResourceTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
@Import(S3MockTestcontainersConfig.class)
class LectureFileApiTest {

    private static final Long SELLER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final byte[] VIDEO = {1, 2, 3, 4, 5};

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FileResourceRepository fileResourceRepository;

    @Test
    void 인증된_인플루언서는_업로드_URL을_받고_파일은_PENDING으로_남는다() throws Exception {
        String response = requestUploadUrl(SELLER_ID, Role.VERIFIED_INFLUENCER, "video/mp4", VIDEO.length)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileId").isNumber())
                .andExpect(jsonPath("$.uploadUrl").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andReturn().getResponse().getContentAsString();

        FileResource file = fileResourceRepository.findById(fileId(response)).orElseThrow();
        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(file.getUploaderUserId()).isEqualTo(SELLER_ID);
        assertThat(URI.create(uploadUrl(response)).getPath()).endsWith("/" + file.getObjectKey());
    }

    @Test
    void 인증된_인플루언서가_아니면_업로드_URL을_받을_수_없다() throws Exception {
        requestUploadUrl(OTHER_USER_ID, Role.INFLUENCER, "video/mp4", VIDEO.length)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 허용하지_않는_형식이면_400_UNSUPPORTED_FILE_CONTENT_TYPE() throws Exception {
        requestUploadUrl(SELLER_ID, Role.VERIFIED_INFLUENCER, "video/webm", VIDEO.length)
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
    void 업로드_URL로_올린_뒤_완료하면_204이고_UPLOADED가_된다() throws Exception {
        String response = uploadUrlResponse("video/mp4", VIDEO.length);
        put(uploadUrl(response), "video/mp4", VIDEO);

        complete(SELLER_ID, fileId(response)).andExpect(status().isNoContent());

        assertThat(fileResourceRepository.findById(fileId(response)).orElseThrow().getStatus())
                .isEqualTo(FileStatus.UPLOADED);
    }

    @Test
    void 올리지_않고_완료하면_409_FILE_NOT_UPLOADED이고_올린_뒤_다시_완료할_수_있다() throws Exception {
        String response = uploadUrlResponse("video/mp4", VIDEO.length);

        complete(SELLER_ID, fileId(response))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_NOT_UPLOADED"));
        assertThat(fileResourceRepository.findById(fileId(response)).orElseThrow().getStatus())
                .isEqualTo(FileStatus.PENDING);

        put(uploadUrl(response), "video/mp4", VIDEO);
        complete(SELLER_ID, fileId(response)).andExpect(status().isNoContent());
    }

    @Test
    void 신청과_다른_크기로_올리면_400_FILE_SIZE_MISMATCH이고_PENDING으로_남는다() throws Exception {
        String response = uploadUrlResponse("video/mp4", VIDEO.length + 1);
        put(uploadUrl(response), "video/mp4", VIDEO);

        complete(SELLER_ID, fileId(response))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_SIZE_MISMATCH"));
        assertThat(fileResourceRepository.findById(fileId(response)).orElseThrow().getStatus())
                .isEqualTo(FileStatus.PENDING);
    }

    @Test
    void 다른_회원이_완료하면_403_NOT_FILE_UPLOADER() throws Exception {
        String response = uploadUrlResponse("video/mp4", VIDEO.length);
        put(uploadUrl(response), "video/mp4", VIDEO);

        complete(OTHER_USER_ID, fileId(response))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_FILE_UPLOADER"));
    }

    @Test
    void 없는_파일을_완료하면_404_FILE_NOT_FOUND() throws Exception {
        complete(SELLER_ID, 999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    }

    @Test
    void 이미_완료한_파일을_다시_완료하면_204() throws Exception {
        String response = uploadUrlResponse("video/mp4", VIDEO.length);
        put(uploadUrl(response), "video/mp4", VIDEO);
        complete(SELLER_ID, fileId(response)).andExpect(status().isNoContent());

        complete(SELLER_ID, fileId(response)).andExpect(status().isNoContent());
    }

    @Test
    void 완료한_파일로_강의를_등록할_수_있다() throws Exception {
        String response = uploadUrlResponse("video/mp4", VIDEO.length);
        put(uploadUrl(response), "video/mp4", VIDEO);
        complete(SELLER_ID, fileId(response)).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/lectures")
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "방송 장비 세팅 강의",
                                  "priceHp": 10000,
                                  "contents": [{"fileId": %d, "title": "1강", "contentType": "VIDEO"}]
                                }
                                """.formatted(fileId(response))))
                .andExpect(status().isCreated());
    }

    private ResultActions requestUploadUrl(Long userId, Role role, String contentType, long fileSize)
            throws Exception {
        return mockMvc.perform(post("/api/v1/lectures/files")
                .with(TestLogin.as(userId, role)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fileType": "LECTURE_VIDEO", "originalFilename": "1강 장비 고르기.mp4",
                         "contentType": "%s", "fileSize": %d}
                        """.formatted(contentType, fileSize)));
    }

    private String uploadUrlResponse(String contentType, long fileSize) throws Exception {
        return requestUploadUrl(SELLER_ID, Role.VERIFIED_INFLUENCER, contentType, fileSize)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions complete(Long userId, Long fileId) throws Exception {
        return mockMvc.perform(post("/api/v1/lectures/files/{fileId}/complete", fileId)
                .with(TestLogin.as(userId, Role.VERIFIED_INFLUENCER)).with(csrf()));
    }

    /** 브라우저처럼 Presigned URL로 S3에 직접 올린다. Content-Type은 서명에 들어간 값과 같아야 한다 */
    private void put(String url, String contentType, byte[] body) throws Exception {
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", contentType)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private static Long fileId(String response) {
        return ((Number) JsonPath.read(response, "$.fileId")).longValue();
    }

    private static String uploadUrl(String response) {
        return JsonPath.read(response, "$.uploadUrl");
    }
}
