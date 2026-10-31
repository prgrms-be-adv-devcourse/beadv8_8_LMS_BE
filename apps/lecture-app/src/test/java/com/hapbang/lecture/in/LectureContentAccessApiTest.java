package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.NOW;
import static com.hapbang.lecture.fixture.LectureFixture.uploadedFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.global.storage.S3FileStorage;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.FileResource;
import com.hapbang.lecture.domain.FileType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureUser;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.FileResourceRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.S3MockTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;

import com.jayway.jsonpath.JsonPath;

// 콘텐츠 시청 URL 발급(판매자·관리자·구매자)을 HTTP부터 S3(S3Mock)까지 관통해 검증한다. 누가 볼 수 있는지의 세부 규칙은 LectureTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
@Import(S3MockTestcontainersConfig.class)
class LectureContentAccessApiTest {

    private static final Long SELLER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long ADMIN_ID = 3L;
    private static final Long BUYER_ID = 4L;
    private static final byte[] VIDEO = {1, 2, 3, 4, 5};

    private static final String REGISTER_BODY = """
            {
              "title": "방송 장비 세팅 강의",
              "priceHp": 10000,
              "contents": [
                {"fileId": %d, "title": "1강 장비 고르기", "contentType": "VIDEO"},
                {"fileId": %d, "title": "장비 목록", "contentType": "DOCUMENT"}
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FileResourceRepository fileResourceRepository;

    @Autowired
    private S3FileStorage s3FileStorage;

    @Autowired
    private LectureUserRepository lectureUserRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 판매자가_영상_시청_URL을_받으면_1시간_뒤_만료되고_URL로_영상을_받는다() throws Exception {
        FileResource video = storedFile(FileType.LECTURE_VIDEO, "1강.mp4", "video/mp4", VIDEO);
        Long lectureId = register(video.getId(), storedFile(FileType.LECTURE_DOCUMENT, "장비 목록.pdf",
                "application/pdf", new byte[] {9}).getId());

        String response = access(SELLER_ID, Role.VERIFIED_INFLUENCER, lectureId, contentId(lectureId, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andReturn().getResponse().getContentAsString();

        LocalDateTime expiresAt = LocalDateTime.parse(JsonPath.read(response, "$.data.expiresAt"));
        assertThat(expiresAt).isCloseTo(LocalDateTime.now().plusHours(1), within(10, ChronoUnit.SECONDS));
        HttpResponse<byte[]> download = http.send(
                HttpRequest.newBuilder(URI.create(JsonPath.read(response, "$.data.url"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(VIDEO);
        assertThat(download.headers().firstValue("Content-Disposition")).isEmpty();
    }

    @Test
    void 판매자가_자료_URL로_받으면_원본_파일명으로_내려받는다() throws Exception {
        Long lectureId = register(storedFile(FileType.LECTURE_VIDEO, "1강.mp4", "video/mp4", VIDEO).getId(),
                storedFile(FileType.LECTURE_DOCUMENT, "장비 목록.pdf", "application/pdf", new byte[] {9}).getId());

        String url = accessUrl(SELLER_ID, Role.VERIFIED_INFLUENCER, lectureId, contentId(lectureId, 1));

        HttpResponse<byte[]> download = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).containsExactly(9);
        assertThat(download.headers().firstValue("Content-Disposition")).hasValueSatisfying(disposition ->
                assertThat(disposition).startsWith("attachment")
                        .contains("filename*=UTF-8''%EC%9E%A5%EB%B9%84%20%EB%AA%A9%EB%A1%9D.pdf"));
    }

    @Test
    void 관리자는_검수_대기_강의의_콘텐츠_URL을_받는다() throws Exception {
        lectureUserRepository.save(LectureUser.from(new UserDto(ADMIN_ID, "관리자", Role.ADMIN, UserStatus.ACTIVE)));
        Long lectureId = registerLecture();

        access(ADMIN_ID, Role.ADMIN, lectureId, contentId(lectureId, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.url").isString());
    }

    @Test
    void 관리자_복제본이_아직_없으면_관리자로_보지_않는다() throws Exception {
        Long lectureId = registerLecture();

        access(ADMIN_ID, Role.ADMIN, lectureId, contentId(lectureId, 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test
    void 다른_회원은_판매_중_강의에서_403_CONTENT_ACCESS_DENIED() throws Exception {
        Long lectureId = registerLecture();
        Long contentId = contentId(lectureId, 0);
        approve(lectureId);

        access(OTHER_USER_ID, Role.USER, lectureId, contentId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONTENT_ACCESS_DENIED"));
    }

    @Test
    void 다른_회원은_검수_대기_강의에서_404_LECTURE_NOT_FOUND() throws Exception {
        Long lectureId = registerLecture();

        access(OTHER_USER_ID, Role.VERIFIED_INFLUENCER, lectureId, contentId(lectureId, 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test
    void 없는_강의면_404_LECTURE_NOT_FOUND() throws Exception {
        access(SELLER_ID, Role.VERIFIED_INFLUENCER, 999_999L, 1L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test
    void 다른_강의의_콘텐츠면_404_LECTURE_CONTENT_NOT_FOUND() throws Exception {
        Long lectureId = registerLecture();
        Long otherLectureContentId = contentId(registerLecture(), 0);

        access(SELLER_ID, Role.VERIFIED_INFLUENCER, lectureId, otherLectureContentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_CONTENT_NOT_FOUND"));
    }

    @Test
    void 교체된_콘텐츠면_404_LECTURE_CONTENT_NOT_FOUND() throws Exception {
        Long lectureId = registerLecture();
        Long replacedContentId = contentId(lectureId, 0);
        reject(lectureId);
        mockMvc.perform(put("/api/v1/lectures/{lectureId}/contents", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contents": [{"fileId": %d, "title": "1강 (재녹음)", "contentType": "VIDEO"}]}
                                """.formatted(fileResourceRepository.save(
                                uploadedFile(SELLER_ID, FileType.LECTURE_VIDEO)).getId())))
                .andExpect(status().isNoContent());

        access(SELLER_ID, Role.VERIFIED_INFLUENCER, lectureId, replacedContentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_CONTENT_NOT_FOUND"));
        access(SELLER_ID, Role.VERIFIED_INFLUENCER, lectureId, contentId(lectureId, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200));
    }

    @Test
    void 판매자는_삭제된_강의의_콘텐츠_URL을_받는다() throws Exception {
        Long lectureId = registerLecture();
        Long contentId = contentId(lectureId, 0);
        deleteLecture(lectureId);

        access(SELLER_ID, Role.VERIFIED_INFLUENCER, lectureId, contentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200));
    }

    @Test
    void 재생_동의_전_구매자는_영상과_자료_모두_403_PLAY_CONSENT_REQUIRED와_주문_ID를_받는다() throws Exception {
        Long lectureId = registerLecture();
        approve(lectureId);
        LectureOrder order = purchase(lectureId);

        for (int index : new int[] {0, 1}) {
            access(BUYER_ID, Role.USER, lectureId, contentId(lectureId, index))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PLAY_CONSENT_REQUIRED"))
                    .andExpect(jsonPath("$.orderId").value(order.getId()))
                    .andExpect(jsonPath("$.orderItemId").value(order.getItems().getFirst().getId()));
        }
    }

    @Test
    void 재생_동의한_구매자는_영상_URL로_영상을_받는다() throws Exception {
        Long lectureId = register(storedFile(FileType.LECTURE_VIDEO, "1강.mp4", "video/mp4", VIDEO).getId(),
                storedFile(FileType.LECTURE_DOCUMENT, "장비 목록.pdf", "application/pdf", new byte[] {9}).getId());
        approve(lectureId);
        consentToPlay(purchase(lectureId));

        String url = accessUrl(BUYER_ID, Role.USER, lectureId, contentId(lectureId, 0));

        HttpResponse<byte[]> download = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(VIDEO);
    }

    @Test
    void 구매_확정한_구매자는_판매자가_삭제한_강의의_콘텐츠도_받는다() throws Exception {
        Long lectureId = registerLecture();
        approve(lectureId);
        Long contentId = contentId(lectureId, 1);
        consentToPlay(purchase(lectureId));
        deleteLecture(lectureId);

        access(BUYER_ID, Role.USER, lectureId, contentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.url").isString());
    }

    @Test
    void 구매_취소한_회원은_콘텐츠를_볼_수_없다() throws Exception {
        Long lectureId = registerLecture();
        approve(lectureId);
        Long contentId = contentId(lectureId, 0);
        LectureOrder order = purchase(lectureId);
        transactionTemplate.executeWithoutResult(status -> LectureOrderFixture.cancel(
                lectureOrderRepository.findById(order.getId()).orElseThrow(), order.getItems().getFirst().getId(),
                LocalDateTime.now(LectureConfig.ZONE)));

        access(BUYER_ID, Role.USER, lectureId, contentId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONTENT_ACCESS_DENIED"));
        deleteLecture(lectureId);
        access(BUYER_ID, Role.USER, lectureId, contentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        Long lectureId = registerLecture();

        mockMvc.perform(get("/api/v1/lectures/{lectureId}/contents/{contentId}/access", lectureId,
                        contentId(lectureId, 0)))
                .andExpect(status().isUnauthorized());
    }

    private String accessUrl(Long userId, Role role, Long lectureId, Long contentId) throws Exception {
        String response = access(userId, role, lectureId, contentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.data.url");
    }

    private ResultActions access(Long userId, Role role, Long lectureId, Long contentId) throws Exception {
        return mockMvc.perform(get("/api/v1/lectures/{lectureId}/contents/{contentId}/access", lectureId, contentId)
                .with(TestLogin.as(userId, role)));
    }

    private void approve(Long lectureId) throws Exception {
        mockMvc.perform(post("/api/v1/lectures/admin/{lectureId}/inspection/approve", lectureId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf()))
                .andExpect(status().isNoContent());
    }

    private void deleteLecture(Long lectureId) throws Exception {
        mockMvc.perform(delete("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf()))
                .andExpect(status().isNoContent());
    }

    /** 주문 생성 API(HP 보류)가 생기기 전에는 픽스처로 주문을 저장한다 */
    private LectureOrder purchase(Long lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        return lectureOrderRepository.save(
                LectureOrderFixture.order(BUYER_ID, List.of(lecture), LocalDateTime.now(LectureConfig.ZONE)));
    }

    private void consentToPlay(LectureOrder order) throws Exception {
        mockMvc.perform(post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/play-consent", order.getId(),
                        order.getItems().getFirst().getId())
                        .with(TestLogin.as(BUYER_ID, Role.USER)).with(csrf()))
                .andExpect(status().isOk());
    }

    private void reject(Long lectureId) throws Exception {
        mockMvc.perform(post("/api/v1/lectures/admin/{lectureId}/inspection/reject", lectureId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "영상 음질이 너무 낮습니다."}
                                """))
                .andExpect(status().isNoContent());
    }

    /** 본문 없이(S3 불필요) 업로드를 마친 영상·자료로 강의를 등록한다(검수 대기) */
    private Long registerLecture() throws Exception {
        return register(fileResourceRepository.save(uploadedFile(SELLER_ID, FileType.LECTURE_VIDEO)).getId(),
                fileResourceRepository.save(uploadedFile(SELLER_ID, FileType.LECTURE_DOCUMENT)).getId());
    }

    /** 판매자가 영상·자료 순서로 강의를 등록한다(검수 대기) */
    private Long register(Long videoFileId, Long documentFileId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/lectures")
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY.formatted(videoFileId, documentFileId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    /** 판매자가 상세 조회로 보는 index번째 콘텐츠의 ID */
    private Long contentId(Long lectureId, int index) throws Exception {
        String response = mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.contents[%d].contentId".formatted(index))).longValue();
    }

    /** 판매자가 올린 파일을 업로드 완료 상태로 저장하고 본문을 S3Mock에 둔다 */
    private FileResource storedFile(FileType fileType, String filename, String contentType, byte[] body) {
        FileResource file = FileResource.create(SELLER_ID, fileType, filename, contentType, body.length, NOW);
        file.markUploaded(NOW);
        FileResource saved = fileResourceRepository.save(file);
        s3FileStorage.upload(saved.getObjectKey(), contentType, body.length, new ByteArrayInputStream(body));
        return saved;
    }
}
