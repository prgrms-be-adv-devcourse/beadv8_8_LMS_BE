package com.hapbang.auxiliary.notification.app;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 종류 코드별 문구 템플릿 모음. N5 11종({@link AlertTemplate}, ND-075), N6 7종({@link AppearanceCancelRequestTemplates}·
 * {@link LectureObjectionTemplates}, ND-085), 하루 묶음({@link PayoutDailyTemplate}, ND-100), N7 지갑 4종({@link WalletAlertTemplates}, ND-108),
 * N7 출연 10종({@link AppearanceProgressTemplates}, ND-113), N7 강의 7종({@link LectureProgressTemplates}, ND-117),
 * N7 회원 3종({@link MemberReviewTemplates}, ND-121), N7 고객센터 2종({@link CustomerSupportAlertTemplates}, ND-126)과
 * 빈으로 더한 템플릿을 함께 둔다. 같은 종류가 둘이면 기동에 실패한다
 */
@Component
public class NotificationTemplates {

    private final Map<String, NotificationTemplate> byKind;

    public NotificationTemplates(ObjectProvider<NotificationTemplate> templates) {
        Stream<NotificationTemplate> builtIn = Stream.<NotificationTemplate[]>of(AlertTemplate.values(),
                AppearanceCancelRequestTemplates.values(), AppearanceProgressTemplates.values(),
                LectureObjectionTemplates.values(), LectureProgressTemplates.values(), PayoutDailyTemplate.values(),
                WalletAlertTemplates.values(), MemberReviewTemplates.values(), CustomerSupportAlertTemplates.values())
                .flatMap(Arrays::stream);
        this.byKind = Stream.concat(builtIn, templates.orderedStream())
                .collect(Collectors.toUnmodifiableMap(NotificationTemplate::kind, t -> t));
    }

    /**
     * @throws IllegalStateException 그 종류의 템플릿이 없을 때(코드 결함이라 재시도 뒤 DLT로 간다)
     */
    public Function<Map<String, Object>, String> renderer(String kind) {
        NotificationTemplate template = byKind.get(kind);
        if (template == null) {
            throw new IllegalStateException("알림 종류의 문구 템플릿이 없습니다: " + kind);
        }
        return template::render;
    }
}
