package com.hapbang.auxiliary.search.lecture.out;

import java.util.List;
import java.util.Objects;

import com.hapbang.auxiliary.search.common.app.HangulKeys;

/**
 * 강의 검색 문서의 원문과 그 초성·자모 칸(#973). 칸 맞추기가 원문으로 다시 만든 값과 비교한다.
 *
 * @param lectureId             강의 ID(문서 ID)
 * @param title                 제목 원문
 * @param titleChosung          제목 초성 칸
 * @param titleJamo             제목 자모 칸
 * @param sellerNickname        판매자 닉네임 원문
 * @param sellerNicknameChosung 판매자 닉네임 초성 칸
 * @param sellerNicknameJamo    판매자 닉네임 자모 칸
 */
public record LectureHangulKeys(long lectureId, String title, List<String> titleChosung, List<String> titleJamo,
                                String sellerNickname, List<String> sellerNicknameChosung,
                                List<String> sellerNicknameJamo) {

    /** 초성·자모 칸이 원문으로 다시 만든 값과 같은지. 다르면 롤링 배포 중 옛 pod가 원문만 바꾼 문서다 */
    public boolean matchesSource() {
        return Objects.equals(titleChosung, HangulKeys.withCompact(HangulKeys.chosung(title)))
                && Objects.equals(titleJamo, HangulKeys.withCompact(HangulKeys.jamo(title)))
                && Objects.equals(sellerNicknameChosung, HangulKeys.withCompact(HangulKeys.chosung(sellerNickname)))
                && Objects.equals(sellerNicknameJamo, HangulKeys.withCompact(HangulKeys.jamo(sellerNickname)));
    }
}
