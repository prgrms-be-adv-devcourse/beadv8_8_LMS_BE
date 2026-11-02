package com.hapbang.lecture.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.hapbang.shared.lecture.domain.LectureObjectionCommandType;
import com.hapbang.shared.lecture.domain.LectureObjectionFailureCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 고객센터가 보낸 이의 제기 명령 하나를 처리한 기록 (#278, D60 (3)·D71). 명령 ID마다 한 행이다.
 * <ul>
 *     <li>같은 명령 ID·같은 내용이 다시 오면 이 행의 결과를 재현하고 항목을 다시 바꾸지 않는다.</li>
 *     <li>같은 명령 ID·다른 내용이면 충돌이다({@link LectureObjectionFailureCode#OBJECTION_COMMAND_CONFLICT}). 행은 그대로다.</li>
 *     <li>내용 비교는 요청 값 지문(SHA-256, {@link #fingerprint})으로 한다.</li>
 *     <li>만든 뒤 바꾸거나 지우지 않는다.</li>
 * </ul>
 */
@Entity
@Table(name = "lecture_objection_command", schema = "lectures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureObjectionCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String commandId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LectureObjectionCommandType commandType;

    @Column(nullable = false)
    private Long inquiryId;

    @Column(nullable = false)
    private Long orderItemId;

    @Column(nullable = false, length = 64)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LectureObjectionCommandOutcome outcome;

    /** 거절 사유. 반영했으면 없다 */
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private LectureObjectionFailureCode failureCode;

    @Column(nullable = false)
    private LocalDateTime processedAt;

    private LectureObjectionCommand(String commandId, LectureObjectionCommandType commandType, Long inquiryId,
                                    Long orderItemId, String payloadHash, LectureObjectionFailureCode failureCode,
                                    LocalDateTime processedAt) {
        this.commandId = Objects.requireNonNull(commandId, "명령 ID는 필수입니다.");
        this.commandType = Objects.requireNonNull(commandType, "명령 종류는 필수입니다.");
        this.inquiryId = Objects.requireNonNull(inquiryId, "문의 ID는 필수입니다.");
        this.orderItemId = Objects.requireNonNull(orderItemId, "주문 항목 ID는 필수입니다.");
        this.payloadHash = Objects.requireNonNull(payloadHash, "요청 값 지문은 필수입니다.");
        this.outcome = failureCode == null ? LectureObjectionCommandOutcome.SUCCEEDED : LectureObjectionCommandOutcome.REFUSED;
        this.failureCode = failureCode;
        this.processedAt = Objects.requireNonNull(processedAt, "처리 시각은 필수입니다.");
    }

    /** 항목에 반영한 명령 */
    public static LectureObjectionCommand succeeded(String commandId, LectureObjectionCommandType commandType,
                                                    Long inquiryId, Long orderItemId, String payloadHash,
                                                    LocalDateTime now) {
        return new LectureObjectionCommand(commandId, commandType, inquiryId, orderItemId, payloadHash, null, now);
    }

    /** 업무 규칙으로 거절한 명령 */
    public static LectureObjectionCommand refused(String commandId, LectureObjectionCommandType commandType,
                                                  Long inquiryId, Long orderItemId, String payloadHash,
                                                  LectureObjectionFailureCode failureCode, LocalDateTime now) {
        return new LectureObjectionCommand(commandId, commandType, inquiryId, orderItemId, payloadHash,
                Objects.requireNonNull(failureCode, "거절 사유는 필수입니다."), now);
    }

    /** 같은 내용의 명령인지. 지문은 명령 종류를 포함한다 */
    public boolean hasPayload(String payloadHash) {
        return this.payloadHash.equals(payloadHash);
    }

    public boolean isSucceeded() {
        return outcome == LectureObjectionCommandOutcome.SUCCEEDED;
    }

    /**
     * 명령 내용의 지문. 명령 종류와 값들을 {@code |}로 이은 문자열의 SHA-256이다({@code null}은 문자열 "null").
     * 값의 순서를 바꾸면 지난 명령과 비교할 수 없으므로 종류마다 순서를 고정한다.
     */
    public static String fingerprint(LectureObjectionCommandType commandType, Object... values) {
        String payload = Stream.concat(Stream.of(commandType.name()), Stream.of(values).map(String::valueOf))
                .collect(Collectors.joining("|"));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }
}
