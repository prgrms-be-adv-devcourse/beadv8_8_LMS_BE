package com.hapbang.appearance.global.web;

import org.springframework.core.MethodParameter;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;

/**
 * {@link LoginMember}가 붙은 파라미터에 현재 회원 ID를 넣는다.
 * <ul>
 *     <li>헤더가 없거나 비어 있으면 401 {@code LOGIN_REQUIRED}</li>
 *     <li>숫자가 아니거나 1보다 작으면 400 {@code INVALID_REQUEST}</li>
 * </ul>
 */
// TODO(security): 세션 키가 정해지면 헤더 대신 세션에서 회원 ID를 꺼내도록 이 클래스만 바꾼다.
public class LoginMemberArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(LoginMember.class)
                && Long.class.equals(parameter.getParameterType());
    }

    @Override
    public Long resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String value = webRequest.getHeader(AppearanceHeaders.USER_ID);
        if (!StringUtils.hasText(value)) {
            throw new AppearanceException(AppearanceErrorCode.LOGIN_REQUIRED);
        }
        return parseMemberId(value.trim());
    }

    private Long parseMemberId(String value) {
        try {
            long memberId = Long.parseLong(value);
            if (memberId < 1) {
                throw invalidMemberId();
            }
            return memberId;
        } catch (NumberFormatException exception) {
            throw invalidMemberId();
        }
    }

    private AppearanceException invalidMemberId() {
        return new AppearanceException(AppearanceErrorCode.INVALID_REQUEST, "회원 ID 형식이 올바르지 않습니다.");
    }
}
