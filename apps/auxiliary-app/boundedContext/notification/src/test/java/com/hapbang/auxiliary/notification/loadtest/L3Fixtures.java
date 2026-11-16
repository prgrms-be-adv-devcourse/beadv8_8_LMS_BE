package com.hapbang.auxiliary.notification.loadtest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;

/**
 * L3 저장→실시간 전달 fixture(N15, NT-LOAD-L3, ND-193). 활성 회원 N명과, 그 회원들에게 차례로 가는 충전 완료 이벤트를 만든다.
 * 충전 완료는 받는 사람 한 명에게 새 알림 한 행이라(같은 사건 없음) 저장 행 하나가 프레임 하나다. 투입 속도는 시험 스크립트가 정한다.
 */
public final class L3Fixtures {

    /**
     * @param members   회원 가입 이벤트(연결 전에 넣고 복제본을 확인한다)
     * @param receivers 연결할 회원 ID
     * @param alerts    알림 이벤트(넣는 순서)
     */
    public record L3Fixture(List<B0Record> members, List<Long> receivers, List<B0Record> alerts) {
    }

    private static final long FIRST_MEMBER = 9_400_001L;
    private static final long FIRST_CHARGE = 82_000_001L;
    private static final Instant COMPLETED_AT = Instant.parse("2026-11-01T00:00:00Z");
    private static final JacksonJsonSerializer<Object> SERIALIZER = new JacksonJsonSerializer<>();

    private L3Fixtures() {
    }

    public static L3Fixture generate(int receiverCount, int alertCount) {
        List<B0Record> members = new ArrayList<>();
        List<Long> receivers = new ArrayList<>();
        for (int i = 0; i < receiverCount; i++) {
            long id = FIRST_MEMBER + i;
            receivers.add(id);
            members.add(record(B0EventType.USER_JOINED, String.valueOf(id),
                    new UserJoinedEvent(new UserDto(id, "회원" + id, Role.USER, UserStatus.ACTIVE, 1L)),
                    B0Category.MEMBER));
        }
        List<B0Record> alerts = new ArrayList<>();
        for (int i = 0; i < alertCount; i++) {
            long chargeId = FIRST_CHARGE + i;
            ChargeCompletedEvent event = new ChargeCompletedEvent(chargeId, receivers.get(i % receiverCount), 1_000L,
                    1_000L, COMPLETED_AT.plusSeconds(i));
            alerts.add(record(B0EventType.CHARGE, "point-charge:" + chargeId, event, B0Category.NORMAL));
        }
        return new L3Fixture(List.copyOf(members), List.copyOf(receivers), List.copyOf(alerts));
    }

    private static B0Record record(B0EventType type, String key, Object event, B0Category category) {
        String json = new String(SERIALIZER.serialize(type.topic(), event), StandardCharsets.UTF_8);
        return new B0Record(type.topic(), key, type.eventClass().getName(), json, category, type, event);
    }
}
