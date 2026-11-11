package com.hapbang.member.app;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.Bio;
import com.hapbang.member.domain.ContentCategories;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.Nickname;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.ContentCategoryRepository;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 내 정보 수정 (닉네임·콘텐츠 분야·자기소개). 보내지 않은 값(null)은 바꾸지 않는다(PATCH).
 * 무엇이든 바뀌면 version이 1 오르고 UserModifiedEvent를 한 번 발행한다. 바뀐 것이 없으면 발행하지 않는다.
 * 다른 회원이 쓰는 닉네임이면 DUPLICATE_NICKNAME (#646).
 * 분야·자기소개를 새로 넣거나 바꾸는 것은 인플루언서만 하고, 지우기(빈 목록·빈 문자열)와 지금 값 그대로 보내기는 누구나 한다 (#711, Q16).
 * 검사는 모두 바꾸기 전에 해서, 하나라도 어기면 아무것도 바꾸지 않는다
 */
@Service
@RequiredArgsConstructor
public class MemberUpdateProfileUseCase {

    private final MemberSupport memberSupport;
    private final ContentCategoryRepository contentCategoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public User update(Long userId, String nickname, List<String> contentCategories, String bio) {
        User user = memberSupport.findUser(userId);
        // 규칙 검사를 바꾸기 전에 모두 한다 (닉네임 중복 확인보다도 먼저)
        String validatedNickname = nickname == null ? null : Nickname.validate(nickname);
        List<String> validatedCategories = contentCategories == null ? null : ContentCategories.validate(contentCategories);
        String validatedBio = bio == null ? null : Bio.validate(bio);
        checkInfluencerProfileAllowed(user, validatedCategories, bio != null, validatedBio);
        if (validatedCategories != null) {
            checkSelectable(user, validatedCategories);
        }

        boolean changed = false;
        if (validatedNickname != null && !validatedNickname.equals(user.getNickname())) {
            // 바꾸기 전에 확인한다. 내 닉네임의 대소문자만 바꾸면 통과
            memberSupport.checkNicknameAvailable(validatedNickname, userId);
            user.changeNickname(validatedNickname);
            changed = true;
        }
        if (contentCategories != null) {
            changed |= user.changeContentCategories(contentCategories);
        }
        if (bio != null) {
            changed |= user.changeBio(bio);
        }
        if (changed) {
            // 역할 변경과 겹치거나 같은 닉네임이 먼저 저장되면 409 (이벤트 발행 전)
            memberSupport.flush();
            eventPublisher.publishEvent(new UserModifiedEvent(user.toDto()));
        }
        return user;
    }

    /**
     * 분야·자기소개를 새로 넣거나 바꾸는 것은 인플루언서·인증된 인플루언서만 한다. 역할은 토큰이 아니라 DB의 회원 역할로 본다
     * (토큰의 역할은 최대 15분 늦게 바뀐다). 누구나 할 수 있는 것: 빈 값으로 지우기(강등된 회원이 남은 값을 지울 수 있게),
     * 지금 값과 같은 값을 그대로 보내기(강등된 회원의 화면이 닉네임만 바꾸면서 남은 값을 함께 보내도 되게, Q16)
     */
    private void checkInfluencerProfileAllowed(User user, List<String> validatedCategories, boolean bioSent,
                                               String validatedBio) {
        boolean setsCategories = validatedCategories != null && !validatedCategories.isEmpty()
                && !validatedCategories.equals(user.getContentCategories());
        boolean setsBio = bioSent && validatedBio != null && !validatedBio.equals(user.getBio());
        if ((setsCategories || setsBio) && !user.getRole().isInfluencerOrAbove()) {
            throw new MemberException(MemberErrorCode.INFLUENCER_PROFILE_NOT_ALLOWED);
        }
    }

    /**
     * 지금 값에 없던, 새로 추가하는 분야만 목록에 있고 새로 고를 수 있는지(active) 확인한다.
     * 이미 고른 분야는 목록에서 꺼졌어도 다시 보내면 그대로 둔다 (Q50)
     */
    private void checkSelectable(User user, List<String> codes) {
        List<String> current = user.getContentCategories();
        List<String> added = codes.stream().filter(code -> !current.contains(code)).toList();
        if (!added.isEmpty() && contentCategoryRepository.countByCodeInAndActiveTrue(added) != added.size()) {
            throw new MemberException(MemberErrorCode.INVALID_CONTENT_CATEGORY);
        }
    }
}
