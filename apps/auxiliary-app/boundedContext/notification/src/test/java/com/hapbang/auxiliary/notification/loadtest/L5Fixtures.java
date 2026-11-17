package com.hapbang.auxiliary.notification.loadtest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;

/**
 * L5 예약 배치 fixture(N15, NT-LOAD-L5S·L5B·L5M·L5F, ND-191). 출연 요청 도착 이벤트 하나가 게스트에게 출연 요청 만료 임박 예약 하나를
 * 만든다(보낼 시각 = 수락 기한 − 12시간, {@code ScheduleTimes.requestExpiring}). 그래서 수락 기한을 "보낼 시각 + 12시간"으로 주면 모든
 * 예약의 보낼 시각이 같아진다.
 * <ul>
 *     <li>회원은 모두 활성이다: 정상 게스트 1,000명, 호스트 50명, 실패 전용 게스트 10명. 실패 전용 회원의 복제본은 예약 생성 뒤 격리
 *     DB에서 지운다(시험 스크립트). 실패 전용 회원은 다른 예약의 게스트·호스트로 쓰지 않는다.</li>
 *     <li>정상·실패 예약은 같은 보낼 시각이고, 실패 예약은 요청 순서 곳곳(100건마다 51번째)에 둔다.</li>
 *     <li>기한 지남 예약({@value #EXPIRED_ROWS}건)은 L5-F에서만, 따로 정한 업무 기한으로 만들어 별도로 넣는다(ND-194).</li>
 * </ul>
 */
public final class L5Fixtures {

    /** 시나리오별 정상·실패 예약 수(ND-192·ND-194) */
    public enum Scenario {
        S(100, 0), B(101, 0), M(1_000, 0), F(990, 10);

        private final int normal;
        private final int failing;

        Scenario(int normal, int failing) {
            this.normal = normal;
            this.failing = failing;
        }

        public int normal() {
            return normal;
        }

        public int failing() {
            return failing;
        }
    }

    /**
     * @param members  회원 가입 이벤트(먼저 넣고 복제본을 확인한다)
     * @param normal   정상 예약을 만드는 요청 도착
     * @param failing  실패 예약을 만드는 요청 도착
     * @param main     정상·실패 요청을 넣는 순서
     * @param expired  기한 지남 예약을 만드는 요청 도착(L5-F만)
     * @param failingReceivers 실패 전용 회원 ID
     */
    public record L5Fixture(List<B0Record> members, List<B0Record> normal, List<B0Record> failing, List<B0Record> main,
                            List<B0Record> expired, List<Long> failingReceivers) {

        public List<B0Record> allRequests() {
            List<B0Record> all = new ArrayList<>(main);
            all.addAll(expired);
            return all;
        }
    }

    /** L5-F의 기한 지남 예약 수 */
    public static final int EXPIRED_ROWS = 20;

    private static final long FIRST_GUEST = 9_300_001L;
    private static final int GUESTS = 1_000;
    private static final long FIRST_HOST = 9_301_001L;
    private static final int HOSTS = 50;
    private static final long FIRST_FAILING = 9_302_001L;
    private static final long FIRST_REQUEST = 81_000_001L;
    private static final JacksonJsonSerializer<Object> SERIALIZER = new JacksonJsonSerializer<>();

    private L5Fixtures() {
    }

    /**
     * @param sendAtKst       정상·실패 예약의 보낼 시각(KST)
     * @param expiredDeadline 기한 지남 예약의 업무 기한(KST). L5-F에서만 쓴다
     */
    public static L5Fixture generate(Scenario scenario, LocalDateTime sendAtKst, LocalDateTime expiredDeadline) {
        List<B0Record> members = new ArrayList<>();
        for (int i = 0; i < GUESTS; i++) {
            members.add(member(FIRST_GUEST + i));
        }
        for (int i = 0; i < HOSTS; i++) {
            members.add(member(FIRST_HOST + i));
        }
        List<Long> failingReceivers = new ArrayList<>();
        for (int i = 0; i < scenario.failing(); i++) {
            failingReceivers.add(FIRST_FAILING + i);
            members.add(member(FIRST_FAILING + i));
        }

        long requestId = FIRST_REQUEST;
        List<B0Record> normal = new ArrayList<>();
        List<B0Record> failing = new ArrayList<>();
        List<B0Record> main = new ArrayList<>();
        int normalIndex = 0;
        int failingIndex = 0;
        for (int i = 0; i < scenario.normal() + scenario.failing(); i++) {
            boolean fail = failingIndex < scenario.failing() && i % 100 == 50;
            long guest = fail ? failingReceivers.get(failingIndex) : FIRST_GUEST + (normalIndex % GUESTS);
            long host = FIRST_HOST + (i % HOSTS);
            B0Record record = request(requestId++, host, guest, sendAtKst.plusHours(12), "L5 요청 " + i);
            (fail ? failing : normal).add(record);
            main.add(record);
            if (fail) {
                failingIndex++;
            } else {
                normalIndex++;
            }
        }
        if (failingIndex != scenario.failing()) {
            throw new IllegalStateException("실패 예약을 모두 배치하지 못했다: " + failingIndex);
        }

        List<B0Record> expired = new ArrayList<>();
        if (scenario == Scenario.F) {
            for (int i = 0; i < EXPIRED_ROWS; i++) {
                expired.add(request(requestId++, FIRST_HOST + (i % HOSTS), FIRST_GUEST + i, expiredDeadline,
                        "L5 기한 지남 " + i));
            }
        }
        return new L5Fixture(List.copyOf(members), List.copyOf(normal), List.copyOf(failing), List.copyOf(main),
                List.copyOf(expired), List.copyOf(failingReceivers));
    }

    private static B0Record member(long id) {
        UserJoinedEvent event = new UserJoinedEvent(new UserDto(id, "회원" + id, Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        return record(B0EventType.USER_JOINED, String.valueOf(id), event, B0Category.MEMBER);
    }

    private static B0Record request(long requestId, long host, long guest, LocalDateTime acceptDeadline, String title) {
        AppearanceRequestDto request = new AppearanceRequestDto(requestId, host, guest, title, 50_000L,
                acceptDeadline.plusDays(30), acceptDeadline);
        return record(B0EventType.APPEARANCE_REQUEST_SENT, "appearance-request:" + requestId,
                new AppearanceRequestSentEvent(request), B0Category.NORMAL);
    }

    private static B0Record record(B0EventType type, String key, Object event, B0Category category) {
        String json = new String(SERIALIZER.serialize(type.topic(), event), StandardCharsets.UTF_8);
        return new B0Record(type.topic(), key, type.eventClass().getName(), json, category, type, event);
    }
}
