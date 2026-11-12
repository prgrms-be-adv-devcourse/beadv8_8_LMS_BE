package com.hapbang.auxiliary.notification.app;

import java.util.Map;

/**
 * N9 채팅 단체방 3종의 문구(#921, ND-130, 알림 종류 문서 0.12). 값({@link GroupRoomAlerts}가 만든다)의 모집글 제목만 쓴다. 호스트
 * 닉네임과 폐쇄 방의 기록 조회 기한은 넣지 않는다. 형식 버전 {@code v}는 1이다.
 * <p>
 * 종류 코드는 채팅 방 안 안내({@code NotificationKind})와 이름이 같지만 뜻·받는 사람이 다르다(ND-043).
 * {@link NotificationTemplates}가 다른 템플릿과 함께 등록한다.
 */
public enum GroupRoomAlertTemplates implements NotificationTemplate {
    /** '{모집글 제목}' 단체방에 초대되었습니다. */
    GROUP_MEMBERS_INVITED {
        @Override
        public String render(Map<String, Object> payload) {
            return quoted(payload) + " 단체방에 초대되었습니다.";
        }
    },
    /** '{모집글 제목}' 단체방에서 내보내졌습니다. */
    GROUP_MEMBER_KICKED {
        @Override
        public String render(Map<String, Object> payload) {
            return quoted(payload) + " 단체방에서 내보내졌습니다.";
        }
    },
    /** '{모집글 제목}' 단체방이 닫혔습니다. 새 메시지는 보낼 수 없습니다. */
    GROUP_ROOM_CLOSED {
        @Override
        public String render(Map<String, Object> payload) {
            return quoted(payload) + " 단체방이 닫혔습니다. 새 메시지는 보낼 수 없습니다.";
        }
    };

    /** 값의 형식 버전 */
    public static final int PAYLOAD_VERSION = 1;

    @Override
    public String kind() {
        return name();
    }

    private static String quoted(Map<String, Object> payload) {
        return "'" + payload.get(Keys.TITLE) + "'";
    }

    /** 값의 키. 문구에 쓰는 값만 둔다 */
    static final class Keys {
        /** 모집글 제목(방이 가진 사본, 이벤트 계약 6장) */
        static final String TITLE = "title";

        private Keys() {
        }
    }
}
