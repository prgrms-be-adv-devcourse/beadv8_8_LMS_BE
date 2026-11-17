package com.hapbang.auxiliary.notification.loadtest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestExpiredEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;
import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.billing.event.PayoutCompletedEvent;
import com.hapbang.shared.chat.event.GroupMembersInvitedEvent;
import com.hapbang.shared.customersupport.event.InquiryAnsweredEvent;
import com.hapbang.shared.lecture.domain.LectureConfirmationType;
import com.hapbang.shared.lecture.dto.LectureOrderItemDto;
import com.hapbang.shared.lecture.event.LectureApprovedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemConfirmedEvent;
import com.hapbang.shared.lecture.event.LectureOrderPaidEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;

/**
 * B0 고정 이벤트 생성기(N13, ND-156). seed가 같으면 같은 레코드·같은 manifest를 만든다.
 * <ul>
 *     <li>회원 1,000(활성 950·정지 30·탈퇴 20) → 알림 5,000(정상 4,625 = 지갑 1,850·출연 1,388·강의 925·고객센터 231·채팅
 *     231, 재전송 250, LEGACY 50, 받는 사람 없음 50, 깨진 본문 25). 오류 유형은 서로 겹치지 않는다.</li>
 *     <li>본문은 발행 앱과 같은 Spring Kafka JSON 직렬화기로 만든다. key는 발행 앱의 {@code @Externalized} 규칙과 같다.</li>
 *     <li>예약을 만드는 이벤트의 기한·보낼 시각은 2027년 이후라 B0 중 예약 알림이 나가지 않는다. 예약 받는 사람은 활성 회원만 쓴다.</li>
 *     <li>기대값은 {@link B0EventType}의 규칙(카탈로그·ND)으로 계산한다: 받을 수 없는 회원은 SKIPPED_RECEIVER(저장 서비스가
 *     받는 사람을 먼저 본다), 같은 사건은 DUPLICATE, 하루 묶음은 판매자·KST 날짜마다 첫 지급 CREATED·나머지 UPDATED.</li>
 * </ul>
 */
public final class B0FixtureGenerator {

    public static final Instant ALERT_START_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    private static final long FIRST_MEMBER = 9_000_001L;
    private static final long FIRST_UNKNOWN = 9_100_001L;
    /** B0 고정 이벤트의 seed(ND-172). 생성기 시험·계약 시험·호환 시험·내보내기가 같이 쓴다 */
    public static final long DEFAULT_SEED = 20261114L;
    /** 예약 종류(카탈로그 예약 R1·R5) */
    private static final String REQUEST_EXPIRING = "APPEARANCE_REQUEST_EXPIRING";
    private static final String ITEM_CONFIRMING = "LECTURE_ORDER_ITEM_CONFIRMING";
    private static final JacksonJsonSerializer<Object> SERIALIZER = new JacksonJsonSerializer<>();

    private final Random random;
    private final List<B0Record> members = new ArrayList<>();
    private final List<B0Record> alerts = new ArrayList<>();
    private final List<Instant> eventTimes = new ArrayList<>();
    private final List<Instant> scheduleTimes = new ArrayList<>();
    private final List<Long> active = new ArrayList<>();
    private final List<Long> inactive = new ArrayList<>();
    private final List<Long> everyone = new ArrayList<>();
    /** 순서 의존 쌍(앞 레코드 → 뒤 레코드). 위치는 삽입을 마친 뒤 정한다 */
    private final Map<B0Record, B0Record> sentToCanceled = new IdentityHashMap<>();
    private final Map<B0Record, B0Record> originalToResend = new IdentityHashMap<>();
    private final List<B0Record[]> paidAndConfirmed = new ArrayList<>();
    private final Sizes sizes;
    private long sequence = 1;

    /**
     * 분류·이벤트별 건수. 회원은 늘 1,000(활성 950·정지 30·탈퇴 20)이다. 같은 seed·같은 크기면 같은 출력이 나온다
     *
     * @param charges            충전 완료(앞 200건은 활성 회원 5명에게 40건씩)
     * @param appearancePayouts  출연 지급
     * @param lecturePayouts     강의 지급(하루 묶음)
     * @param requestsSent       출연 요청 도착(예약 R1 생성)
     * @param requestsCanceled   그중 취소(같은 key, 도착 뒤). {@code requestsSent} 이하
     * @param requestsExpired    출연 요청 만료
     * @param applications       모집 지원
     * @param reviews            출연 리뷰 공개(알림 대상 아님)
     * @param ordersPaid         강의 구매 완료(예약 R5 생성)
     * @param itemsConfirmed     구매 확정(예약 취소). {@code ordersPaid} 이하
     * @param lecturesApproved   강의 승인
     * @param inquiries          문의 답변
     * @param invitations        단체방 초대
     * @param resends            즉시 알림 이벤트 재전송
     * @param legacy             LEGACY(알림 없이 커밋)
     * @param unknownReceivers   받는 사람 복제본 없음(재시도 뒤 DLT, 실패 단계)
     * @param broken             깨진 본문(DLT, 실패 단계)
     */
    public record Sizes(int charges, int appearancePayouts, int lecturePayouts, int requestsSent, int requestsCanceled,
                        int requestsExpired, int applications, int reviews, int ordersPaid, int itemsConfirmed,
                        int lecturesApproved, int inquiries, int invitations, int resends, int legacy,
                        int unknownReceivers, int broken) {

        /** B0 공식 크기(ND-172). 바꾸면 B0 기준선이 달라진다({@code B0FixtureInvarianceTest}) */
        public static final Sizes B0 = new Sizes(1_110, 370, 370, 600, 150, 200, 300, 138, 400, 150, 375, 231, 231,
                250, 50, 50, 25);

        public Sizes {
            if (requestsCanceled > requestsSent || itemsConfirmed > ordersPaid || charges < 200) {
                throw new IllegalArgumentException("크기 조합이 맞지 않는다: " + this);
            }
        }
    }

    private B0FixtureGenerator(long seed, Sizes sizes) {
        this.random = new Random(seed);
        this.sizes = sizes;
    }

    /** 두 단계 투입(ND-166) 순서로 만든다: 정상 단계 레코드 다음에 실패 단계 레코드 */
    public static B0Fixture generate(long seed) {
        return generate(seed, Sizes.B0, true);
    }

    /** 크기를 정해 두 단계 투입 순서로 만든다(N15 L4, ND-189) */
    public static B0Fixture generate(long seed, Sizes sizes) {
        return generate(seed, sizes, true);
    }

    /**
     * @param phased false면 단계를 나누기 전 순서. 단계를 나눠도 레코드·기대 결과·순서 의존 관계가 같은지 비교하는 시험에만 쓴다
     */
    static B0Fixture generate(long seed, boolean phased) {
        return generate(seed, Sizes.B0, phased);
    }

    private static B0Fixture generate(long seed, Sizes sizes, boolean phased) {
        B0FixtureGenerator generator = new B0FixtureGenerator(seed, sizes);
        generator.members();
        generator.alerts();
        if (phased) {
            generator.moveFailuresLast();
        }
        B0Manifest manifest = new Expectation(generator).manifest(seed);
        return new B0Fixture(List.copyOf(generator.members), List.copyOf(generator.alerts), manifest,
                List.copyOf(generator.eventTimes), List.copyOf(generator.scheduleTimes));
    }

    // 회원

    private void members() {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            order.add(i);
        }
        Collections.shuffle(order, random);
        Map<Integer, UserStatus> status = new HashMap<>();
        for (int i = 0; i < 1_000; i++) {
            status.put(order.get(i), i < 30 ? UserStatus.SUSPENDED : i < 50 ? UserStatus.WITHDRAWN : UserStatus.ACTIVE);
        }
        for (int i = 0; i < 1_000; i++) {
            long id = FIRST_MEMBER + i;
            UserStatus userStatus = status.get(i);
            Role role = i % 2 == 0 ? Role.USER : Role.INFLUENCER;
            UserJoinedEvent event = new UserJoinedEvent(new UserDto(id, "회원" + id, role, userStatus, 1L));
            members.add(record(B0EventType.USER_JOINED, String.valueOf(id), event, B0Category.MEMBER));
            everyone.add(id);
            (userStatus == UserStatus.ACTIVE ? active : inactive).add(id);
        }
    }

    // 알림

    private void alerts() {
        List<B0Record> base = new ArrayList<>();
        billing(base);
        List<B0Record> sents = appearance(base);
        List<B0Record[]> lecture = lecture(base);
        for (int i = 0; i < sizes.inquiries(); i++) {
            base.add(inquiry());
        }
        for (int i = 0; i < sizes.invitations(); i++) {
            base.add(invited());
        }
        Collections.shuffle(base, random);
        alerts.addAll(base);
        // 취소는 같은 요청의 도착 뒤(같은 key, 순서 의존)
        for (int i = 0; i < sizes.requestsCanceled(); i++) {
            B0Record sent = sents.get(i);
            B0Record canceled = canceled((AppearanceRequestSentEvent) sent.event());
            insertAfter(sent, canceled);
            sentToCanceled.put(sent, canceled);
        }
        // 구매 확정은 아무 곳(다른 key라 순서가 정해지지 않음, 결과는 같아야 함)
        for (B0Record[] pair : lecture) {
            alerts.add(random.nextInt(alerts.size() + 1), pair[1]);
            paidAndConfirmed.add(pair);
        }
        resends();
        for (int i = 0; i < sizes.legacy(); i++) {
            insertAnywhere(legacy());
        }
        for (int i = 0; i < sizes.unknownReceivers(); i++) {
            insertAnywhere(unknownReceiver());
        }
        for (int i = 0; i < sizes.broken(); i++) {
            insertAnywhere(broken());
        }
    }

    private void billing(List<B0Record> base) {
        List<Long> heavy = pick(active, 5);
        for (int i = 0; i < sizes.charges(); i++) {
            long user = i < 200 ? heavy.get(i / 40) : any();
            base.add(charge(user));
        }
        for (int i = 0; i < sizes.appearancePayouts(); i++) {
            base.add(payout(SettlementSourceType.APPEARANCE_REQUEST, any(), Instant.parse("2026-06-10T03:00:00Z")
                    .plusSeconds(i * 60L)));
        }
        List<Long> sellers = pick(everyone, 30);
        for (int i = 0; i < sizes.lecturePayouts(); i++) {
            Instant paidAt = Instant.parse("2026-06-01T01:00:00Z").plusSeconds(86_400L * random.nextInt(5)
                    + 60L * random.nextInt(600));
            base.add(payout(SettlementSourceType.LECTURE_ORDER_ITEM, sellers.get(random.nextInt(sellers.size())),
                    paidAt));
        }
    }

    private List<B0Record> appearance(List<B0Record> base) {
        List<B0Record> sents = new ArrayList<>();
        for (int i = 0; i < sizes.requestsSent(); i++) {
            List<Long> pair = pick(active, 2);
            LocalDateTime deadline = LocalDateTime.parse("2027-03-01T18:00:00").plusMinutes(i);
            AppearanceRequestDto request = new AppearanceRequestDto(next(), pair.get(0), pair.get(1), "B0 요청 " + i,
                    50_000L, deadline.plusDays(30), deadline);
            B0Record sent = record(B0EventType.APPEARANCE_REQUEST_SENT, "appearance-request:" + request.requestId(),
                    new AppearanceRequestSentEvent(request), B0Category.NORMAL);
            base.add(sent);
            sents.add(sent);
            scheduleTimes.add(deadline.atZone(KST).toInstant());
            scheduleTimes.add(deadline.minusHours(12).atZone(KST).toInstant());
        }
        for (int i = 0; i < sizes.requestsExpired(); i++) {
            List<Long> pair = pick(everyone, 2);
            AppearanceRequestDto request = new AppearanceRequestDto(next(), pair.get(0), pair.get(1), "B0 만료 " + i,
                    30_000L, LocalDateTime.parse("2026-08-01T20:00:00"), LocalDateTime.parse("2026-07-01T18:00:00"));
            LocalDateTime expiredAt = LocalDateTime.parse("2026-07-01T18:00:00");
            base.add(record(B0EventType.APPEARANCE_REQUEST_EXPIRED, "appearance-request:" + request.requestId(),
                    new AppearanceRequestExpiredEvent(request, expiredAt), B0Category.NORMAL));
            eventTimes.add(expiredAt.atZone(KST).toInstant());
        }
        for (int i = 0; i < sizes.applications(); i++) {
            List<Long> pair = pick(everyone, 2);
            LocalDateTime appliedAt = LocalDateTime.parse("2026-06-12T09:00:00").plusMinutes(i);
            AppearanceApplicationDto application = new AppearanceApplicationDto(next(), next(), "B0 모집 " + i,
                    pair.get(0), pair.get(1), appliedAt);
            base.add(record(B0EventType.APPEARANCE_APPLICATION_SUBMITTED,
                    "appearance-application:" + application.applicationId(),
                    new AppearanceApplicationSubmittedEvent(application), B0Category.NORMAL));
            eventTimes.add(appliedAt.atZone(KST).toInstant());
        }
        for (int i = 0; i < sizes.reviews(); i++) {
            List<Long> pair = pick(everyone, 2);
            LocalDateTime publishedAt = LocalDateTime.parse("2026-06-20T12:00:00");
            AppearanceReviewPublishedEvent review = new AppearanceReviewPublishedEvent(next(), next(), pair.get(0),
                    pair.get(1), AppearanceParticipantRole.GUEST, 5, publishedAt);
            base.add(record(B0EventType.APPEARANCE_REVIEW_PUBLISHED, "appearance-review-reviewee:" + pair.get(1),
                    review, B0Category.NORMAL));
            eventTimes.add(publishedAt.atZone(KST).toInstant());
        }
        return sents;
    }

    /** @return (구매 완료 레코드, 구매 확정 레코드) 쌍(B0는 150쌍). 구매 확정은 아직 넣지 않았다 */
    private List<B0Record[]> lecture(List<B0Record> base) {
        List<B0Record[]> pairs = new ArrayList<>();
        List<B0Record> paidRecords = new ArrayList<>();
        for (int i = 0; i < sizes.ordersPaid(); i++) {
            long buyer = active.get(random.nextInt(active.size()));
            List<LectureOrderItemDto> items = new ArrayList<>();
            int count = 1 + random.nextInt(3);
            for (int j = 0; j < count; j++) {
                items.add(new LectureOrderItemDto(next(), next(), "B0 강의 " + i + "-" + j, 10_000L));
            }
            LocalDate paidDate = LocalDate.parse("2027-01-05").plusDays(random.nextInt(5));
            OffsetDateTime paidAt = paidDate.atTime(10, 0).atOffset(KST_OFFSET);
            LectureOrderPaidEvent paid = new LectureOrderPaidEvent(next(), buyer, items, paidAt);
            B0Record record = record(B0EventType.LECTURE_ORDER_PAID, "order:" + paid.orderId(), paid, B0Category.NORMAL);
            base.add(record);
            paidRecords.add(record);
            eventTimes.add(paidAt.toInstant());
            for (int j = 0; j < count; j++) {
                scheduleTimes.add(paidDate.plusDays(7).atTime(9, 0).atZone(KST).toInstant());
                scheduleTimes.add(paidDate.plusDays(8).atStartOfDay(KST).toInstant());
            }
        }
        for (int i = 0; i < sizes.itemsConfirmed(); i++) {
            B0Record paidRecord = paidRecords.get(i);
            LectureOrderPaidEvent paid = (LectureOrderPaidEvent) paidRecord.event();
            LectureOrderItemDto item = paid.items().getFirst();
            // 자동 구매 확정 시각((구매일 + 8일) 00:00)에 확정한다(ND-143 불변식: 확정 시각 ≤ 자동 확정 시각)
            OffsetDateTime confirmedAt = paid.paidAt().toLocalDate().plusDays(8).atStartOfDay()
                    .atOffset(KST_OFFSET);
            LectureOrderItemConfirmedEvent confirmed = new LectureOrderItemConfirmedEvent(item.orderItemId(),
                    paid.buyerUserId(), item.lectureTitle(), LectureConfirmationType.AUTO, confirmedAt);
            pairs.add(new B0Record[] {paidRecord, record(B0EventType.LECTURE_ORDER_ITEM_CONFIRMED,
                    "order-item:" + item.orderItemId(), confirmed, B0Category.NORMAL)});
            eventTimes.add(confirmedAt.toInstant());
        }
        for (int i = 0; i < sizes.lecturesApproved(); i++) {
            OffsetDateTime approvedAt = OffsetDateTime.parse("2026-06-15T10:00:00+09:00").plusMinutes(i);
            LectureApprovedEvent approved = new LectureApprovedEvent(next(), any(), "B0 강의 승인 " + i, 1, approvedAt);
            base.add(record(B0EventType.LECTURE_APPROVED, "lecture:" + approved.lectureId(), approved,
                    B0Category.NORMAL));
            eventTimes.add(approvedAt.toInstant());
        }
        return pairs;
    }

    private B0Record inquiry() {
        Instant answeredAt = Instant.parse("2026-06-20T01:00:00Z").plusSeconds(sequence);
        InquiryAnsweredEvent event = new InquiryAnsweredEvent(next(), next(), any(), "B0 문의", "ETC", answeredAt);
        eventTimes.add(answeredAt);
        return record(B0EventType.INQUIRY_ANSWERED, "inquiry:" + event.inquiryId(), event, B0Category.NORMAL);
    }

    private B0Record invited() {
        List<Long> people = pick(everyone, 1 + 1 + random.nextInt(3));
        Instant invitedAt = Instant.parse("2026-06-25T01:00:00Z").plusSeconds(sequence);
        GroupMembersInvitedEvent event = new GroupMembersInvitedEvent(next(), next(), next(), "B0 단체방",
                people.getFirst(), people.subList(1, people.size()), invitedAt);
        eventTimes.add(invitedAt);
        return record(B0EventType.GROUP_MEMBERS_INVITED, String.valueOf(event.chatRoomId()), event, B0Category.NORMAL);
    }

    private B0Record charge(long user) {
        Instant completedAt = Instant.parse("2026-06-05T01:00:00Z").plusSeconds(sequence);
        ChargeCompletedEvent event = new ChargeCompletedEvent(next(), user, 1_000L, 1_000L, completedAt);
        eventTimes.add(completedAt);
        return record(B0EventType.CHARGE, "point-charge:" + event.chargeId(), event, B0Category.NORMAL);
    }

    private B0Record payout(SettlementSourceType source, long receiver, Instant paidAt) {
        PayoutCompletedEvent event = new PayoutCompletedEvent(next(), receiver, PayoutType.NORMAL, source, next(),
                9_000L, paidAt, "B0 지급");
        eventTimes.add(paidAt);
        B0EventType type = source == SettlementSourceType.LECTURE_ORDER_ITEM ? B0EventType.PAYOUT_LECTURE
                : B0EventType.PAYOUT_APPEARANCE;
        return record(type, "settlement:" + event.settlementId(), event, B0Category.NORMAL);
    }

    private B0Record canceled(AppearanceRequestSentEvent sent) {
        LocalDateTime canceledAt = LocalDateTime.parse("2026-07-10T10:00:00");
        eventTimes.add(canceledAt.atZone(KST).toInstant());
        return record(B0EventType.APPEARANCE_REQUEST_CANCELED, "appearance-request:" + sent.request().requestId(),
                new AppearanceRequestCanceledEvent(sent.request(), canceledAt), B0Category.NORMAL);
    }

    /** 즉시 알림만 만드는 정상 이벤트 중 250건을 원본 뒤에 한 번 더 보낸다(예약 이벤트는 고르지 않는다) */
    private void resends() {
        Set<B0EventType> resendable = Set.of(B0EventType.CHARGE, B0EventType.PAYOUT_APPEARANCE,
                B0EventType.PAYOUT_LECTURE, B0EventType.APPEARANCE_REQUEST_EXPIRED,
                B0EventType.APPEARANCE_APPLICATION_SUBMITTED, B0EventType.LECTURE_APPROVED,
                B0EventType.INQUIRY_ANSWERED, B0EventType.GROUP_MEMBERS_INVITED);
        List<B0Record> candidates = new ArrayList<>(alerts.stream()
                .filter(r -> r.category() == B0Category.NORMAL && resendable.contains(r.type())).toList());
        // 고객센터·지갑 재전송은 반드시 들어가게 한다(범위표). 고른 것과 겹치지 않게 앞에서부터 바꾼다
        Collections.shuffle(candidates, random);
        int count = sizes.resends();
        if (count == 0) {
            // 재전송이 없는 크기에서는 필수 유형을 고르지 않는다(바꿀 자리가 없다). 0보다 크면 지금 규칙 그대로다(B0 불변)
            return;
        }
        List<B0Record> chosen = new ArrayList<>(candidates.subList(0, count));
        int replace = 0;
        for (B0EventType required : List.of(B0EventType.INQUIRY_ANSWERED, B0EventType.CHARGE)) {
            if (chosen.stream().noneMatch(r -> r.type() == required)) {
                chosen.set(replace++, candidates.subList(count, candidates.size()).stream()
                        .filter(r -> r.type() == required).findFirst().orElseThrow());
            }
        }
        for (B0Record original : chosen) {
            B0Record resend = new B0Record(original.topic(), original.key(), original.typeId(), original.value(),
                    B0Category.RESEND, original.type(), original.event());
            insertAfter(original, resend);
            originalToResend.put(original, resend);
        }
    }

    private B0Record legacy() {
        long id = next();
        String json = "{\"commandId\":\"b0-legacy-" + id + "\",\"inquiryId\":" + next() + ",\"orderItemId\":" + id
                + ",\"accepted\":true}";
        return new B0Record(B0Topics.LECTURE, "order-item:" + id, LectureObjectionResolvedEvent.class.getName(), json,
                B0Category.LEGACY, B0EventType.LECTURE_OBJECTION_RESOLVED_LEGACY, null);
    }

    private B0Record unknownReceiver() {
        long user = FIRST_UNKNOWN + sequence;
        ChargeCompletedEvent event = new ChargeCompletedEvent(next(), user, 1_000L, 1_000L,
                Instant.parse("2026-06-30T01:00:00Z"));
        return record(B0EventType.CHARGE, "point-charge:" + event.chargeId(), event, B0Category.UNKNOWN_RECEIVER);
    }

    private B0Record broken() {
        ChargeCompletedEvent event = new ChargeCompletedEvent(next(), any(), 1_000L, 1_000L,
                Instant.parse("2026-06-30T02:00:00Z"));
        String json = json(B0Topics.BILLING, event);
        return new B0Record(B0Topics.BILLING, "point-charge:" + event.chargeId(), ChargeCompletedEvent.class.getName(),
                json.substring(0, json.length() / 2), B0Category.BROKEN, B0EventType.CHARGE, null);
    }

    // 보조

    private B0Record record(B0EventType type, String key, Object event, B0Category category) {
        return new B0Record(type.topic(), key, type.eventClass().getName(), json(type.topic(), event), category, type,
                event);
    }

    private static String json(String topic, Object event) {
        return new String(SERIALIZER.serialize(topic, event), StandardCharsets.UTF_8);
    }

    private void insertAfter(B0Record anchor, B0Record record) {
        int at = indexOf(anchor);
        alerts.add(at + 1 + random.nextInt(alerts.size() - at), record);
    }

    private void insertAnywhere(B0Record record) {
        alerts.add(random.nextInt(alerts.size() + 1), record);
    }

    int indexOf(B0Record record) {
        for (int i = 0; i < alerts.size(); i++) {
            if (alerts.get(i) == record) {
                return i;
            }
        }
        throw new IllegalStateException("레코드가 목록에 없다");
    }

    private long any() {
        return everyone.get(random.nextInt(everyone.size()));
    }

    private List<Long> pick(List<Long> from, int count) {
        List<Long> copy = new ArrayList<>(from);
        Collections.shuffle(copy, random);
        return List.copyOf(copy.subList(0, count));
    }

    private long next() {
        return 80_000_000L + sequence++;
    }

    /**
     * 기대값 계산. 파일 순서로 한 번 훑는다. 다른 파티션 사이 순서가 바뀌어도 결과 수는 같다: 같은 사건(같은 key)은 같은 파티션이고,
     * 하루 묶음은 첫 지급 1건만 CREATED·나머지 UPDATED, 구매 확정과 구매 완료는 어느 쪽이 먼저든 예약 행이 "취소"로 끝난다(ND-034).
     */
    private static final class Expectation {

        private final B0FixtureGenerator g;
        private final Set<Long> eligible;
        private final Map<String, Map<String, Integer>> outcomes = new TreeMap<>();
        private final Map<String, Integer> deadLetters = new TreeMap<>();
        private final Map<String, Integer> failures = new TreeMap<>();
        private final Map<String, Map<String, Integer>> schedules = new TreeMap<>();
        private final Map<Long, Integer> rows = new TreeMap<>();
        private final Map<B0Branch, Integer> coverage = new EnumMap<>(B0Branch.class);
        private final Set<String> notificationKeys = new HashSet<>();
        private final Set<Long> payoutItems = new HashSet<>();
        private final Set<String> bundles = new HashSet<>();
        private final Map<String, String> scheduleStatus = new LinkedHashMap<>();
        private int legacy;
        private int notAlert;

        Expectation(B0FixtureGenerator generator) {
            this.g = generator;
            this.eligible = new HashSet<>(generator.active);
            for (B0Branch branch : B0Branch.values()) {
                coverage.put(branch, 0);
            }
        }

        B0Manifest manifest(long seed) {
            for (B0Record member : g.members) {
                UserJoinedEvent joined = (UserJoinedEvent) member.event();
                hit(joined.user().status() == UserStatus.ACTIVE ? B0Branch.MEMBER_REPLICA_ACTIVE
                        : B0Branch.MEMBER_REPLICA_INACTIVE);
                // 가입 환영(notification-catalog USER_JOINED): 받는 사람은 새 회원, 정지·탈퇴면 받는 사람 제외(ND-018)
                alert(B0Topics.MEMBER, joined.user().id(), "joined:" + joined.user().id(), null, null);
            }
            for (B0Record record : g.alerts) {
                apply(record);
            }
            for (String status : scheduleStatus.values()) {
                String[] parts = status.split("\\|");
                schedules.computeIfAbsent(parts[0], k -> new TreeMap<>()).merge(parts[1], 1, Integer::sum);
            }
            return new B0Manifest(seed, recordsByTopic(), partitionSequences(), outcomes, deadLetters, failures, legacy,
                    notAlert, schedules, rows.values().stream().mapToInt(Integer::intValue).sum(), rows,
                    List.copyOf(g.inactive), g.members.size(), orderedPairs(), orderIndependentPairs(), coverage,
                    phaseRecordsByTopic());
        }

        private void apply(B0Record record) {
            switch (record.category()) {
                case LEGACY -> {
                    legacy++;
                    hit(B0Branch.LECTURE_LEGACY);
                    return;
                }
                case UNKNOWN_RECEIVER -> {
                    deadLetters.merge("NotificationException", 1, Integer::sum);
                    failures.merge("NotificationException", 1 + NotificationKafkaConstants.MAX_RETRIES, Integer::sum);
                    hit(B0Branch.UNKNOWN_RECEIVER_DEAD_LETTER);
                    return;
                }
                case BROKEN -> {
                    deadLetters.merge("DeserializationException", 1, Integer::sum);
                    failures.merge("DeserializationException", 1, Integer::sum);
                    hit(B0Branch.BROKEN_DEAD_LETTER);
                    return;
                }
                default -> {
                }
            }
            String topic = record.topic();
            switch (record.event()) {
                case ChargeCompletedEvent e -> alert(topic, e.userId(), "charge:" + e.chargeId(),
                        B0Branch.BILLING_IMMEDIATE, B0Branch.BILLING_DUPLICATE);
                case PayoutCompletedEvent e when e.sourceType() == SettlementSourceType.LECTURE_ORDER_ITEM -> bundle(
                        topic, e);
                case PayoutCompletedEvent e -> alert(topic, e.receiverUserId(), "payout:" + e.settlementId(),
                        B0Branch.BILLING_IMMEDIATE, B0Branch.BILLING_DUPLICATE);
                case AppearanceRequestSentEvent e -> {
                    alert(topic, e.request().guestId(), "sent:" + e.request().requestId(),
                            B0Branch.APPEARANCE_IMMEDIATE, null);
                    createSchedule(REQUEST_EXPIRING,"request:" + e.request().requestId(), e.request().guestId());
                    hit(B0Branch.APPEARANCE_SCHEDULE_CREATE);
                }
                case AppearanceRequestCanceledEvent e -> {
                    alert(topic, e.request().guestId(), "canceled:" + e.request().requestId(),
                            B0Branch.APPEARANCE_IMMEDIATE, null);
                    cancelSchedule(REQUEST_EXPIRING,"request:" + e.request().requestId(), e.request().guestId());
                    hit(B0Branch.APPEARANCE_SCHEDULE_CANCEL);
                }
                case AppearanceRequestExpiredEvent e -> {
                    alert(topic, e.request().hostId(), "expired:" + e.request().requestId(),
                            B0Branch.APPEARANCE_IMMEDIATE, null);
                    alert(topic, e.request().guestId(), "expired:" + e.request().requestId(),
                            B0Branch.APPEARANCE_IMMEDIATE, null);
                    hit(B0Branch.APPEARANCE_TWO_RECEIVERS);
                }
                case AppearanceApplicationSubmittedEvent e -> alert(topic, e.application().hostId(),
                        "application:" + e.application().applicationId(), B0Branch.APPEARANCE_IMMEDIATE, null);
                case AppearanceReviewPublishedEvent e -> {
                    notAlert++;
                    hit(B0Branch.APPEARANCE_NOT_ALERT);
                }
                case LectureOrderPaidEvent e -> {
                    alert(topic, e.buyerUserId(), "paid:" + e.orderId(), B0Branch.LECTURE_IMMEDIATE, null);
                    for (LectureOrderItemDto item : e.items()) {
                        createSchedule(ITEM_CONFIRMING,"item:" + item.orderItemId(), e.buyerUserId());
                    }
                    hit(B0Branch.LECTURE_SCHEDULE_CREATE);
                }
                case LectureOrderItemConfirmedEvent e -> {
                    alert(topic, e.buyerUserId(), "confirmed:" + e.orderItemId(), B0Branch.LECTURE_IMMEDIATE, null);
                    cancelSchedule(ITEM_CONFIRMING,"item:" + e.orderItemId(), e.buyerUserId());
                    hit(B0Branch.LECTURE_SCHEDULE_CANCEL);
                }
                case LectureApprovedEvent e -> alert(topic, e.sellerUserId(),
                        "approved:" + e.lectureId() + ":" + e.inspectionSequence(), B0Branch.LECTURE_IMMEDIATE, null);
                case InquiryAnsweredEvent e -> alert(topic, e.userId(), "inquiry:" + e.replyId(),
                        B0Branch.CUSTOMERSUPPORT_IMMEDIATE, B0Branch.CUSTOMERSUPPORT_DUPLICATE);
                case GroupMembersInvitedEvent e -> {
                    for (Long invitee : e.invitedUserIds()) {
                        alert(topic, invitee, "invited:" + e.chatRoomId() + ":" + e.inviteRequestId(), null, null);
                    }
                    hit(B0Branch.CHAT_MULTI_RECEIVER);
                }
                default -> throw new IllegalStateException("기대값 규칙이 없는 이벤트: " + record.typeId());
            }
        }

        /** 저장 서비스: 받는 사람 판단 → 같은 사건 → 저장(ND-018·ND-019) */
        private void alert(String topic, long receiver, String sourceKey, B0Branch created, B0Branch duplicate) {
            if (!eligible.contains(receiver)) {
                count(topic, "skipped_receiver");
                hit(B0Branch.SKIPPED_RECEIVER);
                return;
            }
            if (!notificationKeys.add(sourceKey + "#" + receiver)) {
                count(topic, "duplicate");
                if (duplicate != null) {
                    hit(duplicate);
                }
                return;
            }
            count(topic, "created");
            rows.merge(receiver, 1, Integer::sum);
            if (created != null) {
                hit(created);
            }
        }

        /** 하루 묶음: 받는 사람 판단 → 같은 지급 → 판매자·KST 날짜 묶음(ND-032·ND-097) */
        private void bundle(String topic, PayoutCompletedEvent payout) {
            long seller = payout.receiverUserId();
            if (!eligible.contains(seller)) {
                count(topic, "skipped_receiver");
                hit(B0Branch.SKIPPED_RECEIVER);
                return;
            }
            if (!payoutItems.add(payout.settlementId())) {
                count(topic, "duplicate");
                hit(B0Branch.BILLING_DUPLICATE);
                return;
            }
            String bundleKey = seller + "@" + payout.paidAt().atZone(KST).toLocalDate();
            if (bundles.add(bundleKey)) {
                count(topic, "created");
                rows.merge(seller, 1, Integer::sum);
                hit(B0Branch.BILLING_BUNDLE_CREATED);
            } else {
                count(topic, "updated");
                hit(B0Branch.BILLING_BUNDLE_UPDATED);
            }
        }

        private void createSchedule(String kind, String target, long receiver) {
            scheduleStatus.putIfAbsent(kind + "|" + target + "|" + receiver, kind + "|SCHEDULED");
        }

        /** 생성보다 먼저 와도 "취소" 행을 남긴다(ND-034·ND-138). 끝 상태는 어느 순서든 CANCELED */
        private void cancelSchedule(String kind, String target, long receiver) {
            scheduleStatus.put(kind + "|" + target + "|" + receiver, kind + "|CANCELED");
        }

        private void count(String topic, String result) {
            outcomes.computeIfAbsent(topic, k -> new TreeMap<>()).merge(result, 1, Integer::sum);
        }

        private void hit(B0Branch branch) {
            coverage.merge(branch, 1, Integer::sum);
        }

        private Map<String, Integer> recordsByTopic() {
            Map<String, Integer> byTopic = new TreeMap<>();
            for (B0Record record : g.allRecordsInOrder()) {
                byTopic.merge(record.topic(), 1, Integer::sum);
            }
            return byTopic;
        }

        private Map<String, Map<String, Integer>> phaseRecordsByTopic() {
            Map<String, Map<String, Integer>> byPhase = new TreeMap<>();
            for (B0Record record : g.alerts) {
                byPhase.computeIfAbsent(record.category().failurePhase() ? "failure" : "normal", k -> new TreeMap<>())
                        .merge(record.topic(), 1, Integer::sum);
            }
            return byPhase;
        }

        private Map<String, Map<Integer, List<String>>> partitionSequences() {
            Map<String, Map<Integer, List<String>>> sequences = new TreeMap<>();
            for (B0Record record : g.allRecordsInOrder()) {
                sequences.computeIfAbsent(record.topic(), k -> new TreeMap<>())
                        .computeIfAbsent(record.partition(), k -> new ArrayList<>()).add(record.digest());
            }
            return sequences;
        }

        private List<B0Manifest.OrderedPair> orderedPairs() {
            List<B0Manifest.OrderedPair> pairs = new ArrayList<>();
            g.sentToCanceled.forEach((sent, canceled) -> pairs.add(pair("APPEARANCE_REQUEST_SENT>CANCELED", sent,
                    canceled)));
            g.originalToResend.forEach((original, resend) -> pairs.add(pair("RESEND", original, resend)));
            pairs.sort((a, b) -> Integer.compare(a.firstIndex(), b.firstIndex()));
            return pairs;
        }

        private B0Manifest.OrderedPair pair(String relation, B0Record first, B0Record second) {
            return new B0Manifest.OrderedPair(relation, first.topic(), first.key(), g.indexOf(first), second.topic(),
                    second.key(), g.indexOf(second));
        }

        private List<B0Manifest.OrderIndependentPair> orderIndependentPairs() {
            return g.paidAndConfirmed.stream().map(p -> new B0Manifest.OrderIndependentPair(
                    "LECTURE_ORDER_PAID<>ITEM_CONFIRMED", p[0].topic(), p[0].key(), p[1].topic(), p[1].key())).toList();
        }
    }

    /**
     * 실패 단계 레코드(복제본 없음·깨진 본문)를 상대 순서를 지킨 채 맨 뒤로 옮긴다(안정 분할). 실패 레코드는 순서 의존 쌍을 만든 뒤에
     * 끼워 넣어 어떤 쌍에도 들지 않고, 저장 결과에 영향을 주지 않는다(DLT로 감). 정상 레코드끼리의 순서는 그대로다
     */
    private void moveFailuresLast() {
        List<B0Record> normal = alerts.stream().filter(r -> !r.category().failurePhase()).toList();
        List<B0Record> failure = alerts.stream().filter(r -> r.category().failurePhase()).toList();
        alerts.clear();
        alerts.addAll(normal);
        alerts.addAll(failure);
    }

    private List<B0Record> allRecordsInOrder() {
        List<B0Record> all = new ArrayList<>(members);
        all.addAll(alerts);
        return all;
    }
}
