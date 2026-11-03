package com.hapbang.chat.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.hapbang.chat.app.ChatRecruitSourceException.Reason;

/**
 * 출연 앱 없이 단체방을 시험하는 {@link ChatRecruitSource}의 메모리 구현. 출연 내부 API 계약(A1~A3)과 같은 모양으로 답한다.
 * <ul>
 *     <li>지원자 목록은 회원 ID 오름차순 키셋이고, 키셋 반복을 시험하도록 한 페이지를 {@link #applicantPageSize}로 줄인다.</li>
 *     <li>{@link #failWith}로 출연 앱 장애(호출 불가·거절)를 흉내 낸다.</li>
 * </ul>
 */
public class InMemoryChatRecruitSource implements ChatRecruitSource {

    private final Map<Long, ChatRecruit> recruits = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> applicants = new ConcurrentHashMap<>();
    private final AtomicInteger recruitCalls = new AtomicInteger();
    private final AtomicInteger applicantCalls = new AtomicInteger();
    private final AtomicInteger appliedCalls = new AtomicInteger();
    private volatile Reason failure;
    private volatile int applicantPageSize = 2;

    public void putRecruit(Long recruitId, Long hostId, String title, String status) {
        recruits.put(recruitId, new ChatRecruit(recruitId, hostId, title, status));
    }

    /** 지원 이력을 더한다(지원 상태는 보지 않는다: 대기·수락·미선정·지원 취소 모두 이력이다) */
    public void addApplicants(Long recruitId, Long... userIds) {
        applicants.computeIfAbsent(recruitId, ignored -> new TreeSet<>()).addAll(List.of(userIds));
    }

    /** 이후 모든 호출을 이 사유로 실패시킨다. null이면 정상으로 돌린다 */
    public void failWith(Reason reason) {
        this.failure = reason;
    }

    public void reset() {
        recruits.clear();
        applicants.clear();
        recruitCalls.set(0);
        applicantCalls.set(0);
        appliedCalls.set(0);
        failure = null;
        applicantPageSize = 2;
    }

    public int recruitCalls() {
        return recruitCalls.get();
    }

    public int applicantCalls() {
        return applicantCalls.get();
    }

    public int appliedCalls() {
        return appliedCalls.get();
    }

    @Override
    public ChatRecruit getRecruit(Long recruitId) {
        recruitCalls.incrementAndGet();
        failIfSet();
        ChatRecruit recruit = recruits.get(recruitId);
        if (recruit == null) {
            throw new ChatRecruitSourceException(Reason.NOT_FOUND, 404, "RECRUIT_NOT_FOUND", "없음", null);
        }
        return recruit;
    }

    @Override
    public ChatApplicantPage getApplicants(Long recruitId, Long afterUserId, int size) {
        applicantCalls.incrementAndGet();
        failIfSet();
        requireRecruit(recruitId);
        long after = afterUserId == null ? 0 : afterUserId;
        List<Long> rest = applicants.getOrDefault(recruitId, Set.of()).stream()
                .filter(userId -> userId > after)
                .toList();
        int pageSize = Math.min(size, applicantPageSize);
        List<Long> page = new ArrayList<>(rest.subList(0, Math.min(pageSize, rest.size())));
        Long next = rest.size() > pageSize ? page.getLast() : null;
        return new ChatApplicantPage(page, next);
    }

    @Override
    public Set<Long> findApplied(Long recruitId, Collection<Long> userIds) {
        appliedCalls.incrementAndGet();
        failIfSet();
        requireRecruit(recruitId);
        if (userIds.isEmpty() || userIds.size() > MAX_APPLIED_CHECK_SIZE) {
            throw new ChatRecruitSourceException(Reason.REJECTED, 400, "INVALID_REQUEST", "개수 범위 밖", null);
        }
        Set<Long> applied = applicants.getOrDefault(recruitId, Set.of());
        return userIds.stream().filter(applied::contains).collect(Collectors.toSet());
    }

    private void requireRecruit(Long recruitId) {
        if (!recruits.containsKey(recruitId)) {
            throw new ChatRecruitSourceException(Reason.NOT_FOUND, 404, "RECRUIT_NOT_FOUND", "없음", null);
        }
    }

    private void failIfSet() {
        Reason reason = failure;
        if (reason != null) {
            throw new ChatRecruitSourceException(reason, reason == Reason.UNAVAILABLE ? 0 : 400, null, "시험용 장애",
                    null);
        }
    }
}
