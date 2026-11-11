package com.hapbang.coupon.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 발급 수량 대사 판정. 한 쿠폰의 DB 결과와 Redis 기록을 같은 기준 시점으로 맞춰 읽은 값으로
 * {@code 총수량 = DB 발급 완료 + 진행 중 + 거절 보상 대기 + Redis 가용 재고}를 대조한다.
 * <p>
 * 같은 요청은 한 번만 센다. requests의 요청마다 DB 결과로 한 갈래에 넣는다.
 * <ul>
 *     <li>DB ISSUED: 발급 완료(DB 발급 완료 수는 requests에서 이미 지운 요청까지 포함한 DB ISSUED 행 수다). 기록 정리 대상</li>
 *     <li>DB REJECTED + 보상 표식: 재고를 돌려받았으므로 어느 쪽에도 세지 않는다. 접수가 끝난 쿠폰에서만 기록 정리 대상</li>
 *     <li>DB REJECTED + 보상 전 + 지금 epoch: 거절 보상 대기(보상이 재고를 하나 돌려줄 몫)</li>
 *     <li>DB REJECTED + 보상 전 + 이전 epoch: 세지 않는다. 재구축한 재고에 들어 있지 않고 이전 epoch 보상은 재고를 바꾸지 않는다</li>
 *     <li>DB에 없음 + 보상 표식 없음: 진행 중(Kafka·DLT 어디에 있든 한 번). 재발행 대기 시간이 지났으면 재발행 대기로도 센다</li>
 *     <li>DB에 없음 + 보상 표식: 세지 않는다(거절 확정과 보상이 DB를 읽은 뒤에 일어났다)</li>
 * </ul>
 * 이전 epoch 기록(기록의 접수 epoch &lt; 지금 epoch. 접수 epoch가 없는 옛 형식 기록은 판단하지 못해 위 규칙만 쓴다)은 재구축한 재고에 들어 있지
 * 않으므로 식에 세지 않는다.
 * <ul>
 *     <li>DB 최종 결과(ISSUED 또는 REJECTED)가 있으면 보상 표식과 무관하게 기록 정리 대상이다(아래 접수 중 예외를 따른다). 이전 epoch의 늦은
 *     전달·보상은 지금 재고를 바꾸지 않는다.</li>
 *     <li>DB 결과가 없고 보상 표식도 없으면 지우지 않고 남겨 알린다({@link Judgment#staleUnresolved}). 복구가 미처리 0을 확인한 뒤 재구축하므로
 *     정상 경로에서는 없다.</li>
 * </ul>
 * 접수가 아직 열릴 수 있는 동안({@link Input#stillAccepting}, {@link #stillAccepting})에는 ISSUED 기록만 정리하고 REJECTED 기록은 보상
 * 여부·epoch와 무관하게 남긴다. 보상은 그 회원을 users에서도 빼므로, 요청 기록까지 지우면 같은 요청 ID를 다시 보냈을 때 발급 Lua가 새 접수로
 * 받아 재고를 한 번 더 차감한다(접수 경로는 DB를 읽지 않는다). 접수가 끝난 쿠폰(다시 열 수 없거나 발급 종료가 지남)에서만 REJECTED 기록도
 * 정리한다.
 * <p>
 * ISSUED 기록을 지운 뒤 같은 요청 ID를 다시 보내면 같은 요청 재응답(2)이 아니라 이미 발급(−1)이 된다. 회원이 users에 남아 있기 때문이며
 * (재고는 차감되지 않는다), 결과는 발급 요청 상태 조회가 DB에서 ISSUED로 알려 준다.
 * <p>
 * 기준 시점: DB를 먼저 읽고 Redis 기록(재고·회원·요청)을 한 번에(원자적으로) 읽는다. 그러면 DB에서 본 발급 완료는 모두 Redis에서
 * 본 재고에 반영된 요청이고, DB를 읽은 뒤 접수·확정·보상된 요청은 Redis 쪽에서 한 갈래로만 보인다. 그래도 판정이 맞지 않으면 호출자가 한 번 더
 * 읽어 확인한 뒤에만 불일치로 확정한다.
 */
public final class IssueReconciliationRule {

    private IssueReconciliationRule() {
    }

    public static Judgment judge(Input input) {
        long dbIssued = input.dbResults().values().stream()
                .filter(db -> db.result() == CouponIssueResult.ISSUED)
                .count();
        Set<Long> expectedUsers = new HashSet<>();
        List<MissingUser> missingUsers = new ArrayList<>();
        input.dbResults().values().stream()
                .filter(db -> db.result() == CouponIssueResult.ISSUED)
                .forEach(db -> expectUser(input.users(), expectedUsers, missingUsers, db.memberId(), null));

        long inProgress = 0;
        long pendingCompensation = 0;
        long waitingRepublish = 0;
        long staleUnresolved = 0;
        int malformed = 0;
        List<String> finished = new ArrayList<>();
        for (Map.Entry<String, String> entry : input.requests().entrySet()) {
            String requestId = entry.getKey();
            IssueRequestRecord record;
            try {
                record = IssueRequestRecord.parse(entry.getValue());
            } catch (IllegalArgumentException e) {
                malformed++;
                continue;
            }
            DbResult db = input.dbResults().get(requestId);
            boolean stale = stale(record, input.currentEpoch());
            if (db == null) {
                if (record.compensated()) {
                    continue;
                }
                if (stale) {
                    staleUnresolved++;
                    continue;
                }
                inProgress++;
                if (!record.acceptedAt().plus(input.republishAfter()).isAfter(input.now())) {
                    waitingRepublish++;
                }
                expectUser(input.users(), expectedUsers, missingUsers, record.memberId(), requestId);
            } else if (db.result() == CouponIssueResult.ISSUED
                    || (!input.stillAccepting() && (stale || record.compensated()))) {
                finished.add(requestId);
            } else if (stale || record.compensated()) {
                // 접수가 열릴 수 있는 동안 남기는 REJECTED 기록. 식에는 세지 않는다
                continue;
            } else if (input.currentEpoch() != null && db.epoch() == input.currentEpoch()) {
                pendingCompensation++;
                expectUser(input.users(), expectedUsers, missingUsers, record.memberId(), requestId);
            }
        }

        long extraUsers = input.users().stream().filter(member -> !expectedUsers.contains(member)).count();
        Mismatch mismatch = classify(input, dbIssued, inProgress, pendingCompensation, malformed);
        SoldOutRepair soldOutRepair = soldOutRepair(input.stock(), input.soldOut());
        return new Judgment(mismatch, input.quantity(), dbIssued, inProgress, pendingCompensation, input.stock(),
                waitingRepublish, List.copyOf(finished), List.copyOf(missingUsers), extraUsers, soldOutRepair, malformed,
                input.requests().size() - finished.size(), staleUnresolved);
    }

    /**
     * 이 쿠폰이 아직 접수를 받을 수 있는가(REJECTED 기록을 남겨야 하는가). 발급 종료 시각에 {@code grace}를 더한 시각 전이고, DB 상태가 발급
     * 중(OPEN·SUSPENDED. 중단은 재개될 수 있다)이거나 Redis issue-control이 OPEN이면 그렇다. Redis 쪽은 DB와 어긋난 경우를 위한 보수적 판정이다.
     * <p>
     * {@code grace}: 발급 API가 종료 직전에 현재 시각을 읽고 발급 Lua에는 늦게 닿는 접수와 겨루지 않기 위한 여유다. 길게 잡아도 정리가 늦어질 뿐이다.
     */
    public static boolean stillAccepting(CouponStatus status, String redisStatus, Instant issueEndAt, Instant now,
                                         Duration grace) {
        if (!now.isBefore(issueEndAt.plus(grace))) {
            return false;
        }
        return status == CouponStatus.OPEN || status == CouponStatus.SUSPENDED || "OPEN".equals(redisStatus);
    }

    /** 이전 epoch의 접수 기록인가. 접수 epoch가 없는 옛 형식 기록이나 지금 epoch를 모르면 판단하지 않는다(false) */
    static boolean stale(IssueRequestRecord record, Long currentEpoch) {
        return record.epoch() != null && currentEpoch != null && record.epoch() < currentEpoch;
    }

    private static void expectUser(Set<Long> users, Set<Long> expected, List<MissingUser> missing, long memberId,
                                   String requestId) {
        if (expected.add(memberId) && !users.contains(memberId)) {
            missing.add(new MissingUser(memberId, requestId));
        }
    }

    private static Mismatch classify(Input input, long dbIssued, long inProgress, long pendingCompensation,
                                     int malformed) {
        if (input.stock() == null || input.currentEpoch() == null || malformed > 0) {
            // 재고·epoch가 없거나 읽을 수 없는 요청 기록이 있으면 식을 셀 수 없다
            return Mismatch.UNEXPLAINED;
        }
        if (input.stock() < 0) {
            return Mismatch.NEGATIVE_STOCK;
        }
        if (dbIssued > input.quantity()) {
            return Mismatch.DB_OVER_QUANTITY;
        }
        if (dbIssued + inProgress + pendingCompensation + input.stock() != input.quantity()) {
            return Mismatch.UNEXPLAINED;
        }
        return null;
    }

    /** 매진 표시는 재고가 0일 때만 있어야 한다. 실제 고침은 저장소가 그때의 재고로 다시 확인하고 한다 */
    private static SoldOutRepair soldOutRepair(Long stock, boolean soldOut) {
        if (stock == null) {
            return SoldOutRepair.NONE;
        }
        if (stock == 0 && !soldOut) {
            return SoldOutRepair.SET;
        }
        if (stock > 0 && soldOut) {
            return SoldOutRepair.CLEAR;
        }
        return SoldOutRepair.NONE;
    }

    /**
     * @param quantity       쿠폰 총수량
     * @param currentEpoch   Redis issue-control의 epoch(지금 재고의 세대). 없거나 읽을 수 없으면 null
     * @param stock          Redis 가용 재고. key가 없으면 null
     * @param soldOut        매진 표시가 있는가
     * @param users          Redis users(접수한 회원)
     * @param requests       Redis requests(요청 ID → 접수 기록 원문)
     * @param dbResults      이 쿠폰의 DB 발급 요청 결과 전부(요청 ID → 결과). requests보다 먼저 읽은 값이다
     * @param now            기준 시각(재발행 대기 판정)
     * @param republishAfter 재발행 대기 시간(접수 뒤 이 시간이 지나도 DB에 없으면 재발행 대상)
     * @param stillAccepting 쿠폰이 아직 접수를 받을 수 있는가({@link #stillAccepting}). 그러면 REJECTED 기록은 정리하지 않는다
     */
    public record Input(int quantity, Long currentEpoch, Long stock, boolean soldOut, Set<Long> users,
                        Map<String, String> requests, Map<String, DbResult> dbResults, Instant now,
                        Duration republishAfter, boolean stillAccepting) {

        public Input {
            users = Set.copyOf(Objects.requireNonNull(users, "users"));
            requests = Map.copyOf(Objects.requireNonNull(requests, "requests"));
            dbResults = Map.copyOf(Objects.requireNonNull(dbResults, "dbResults"));
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(republishAfter, "republishAfter");
        }
    }

    /** DB 발급 요청 결과 한 건 */
    public record DbResult(long memberId, CouponIssueResult result, long epoch) {
    }

    /**
     * 판정 결과.
     *
     * @param mismatch            불일치 종류. 맞으면 null
     * @param waitingRepublish    재발행 대기 건수(진행 중 가운데 재발행 대기 시간이 지난 것). 적체 차단 판정의 입력이다
     * @param finishedRequestIds  DB 최종 결과를 확인해 requests에서 지워도 되는 요청 ID(ISSUED. 접수가 끝난 쿠폰이면 REJECTED이고 보상 표식
     *                            있음, 이전 epoch이고 DB 결과 있음도)
     * @param missingUsers        users에 있어야 하는데 없는 회원(확정 발급·진행 중·보상 대기의 회원)
     * @param extraUsers          users에 있지만 확정 발급·진행 중·보상 대기 어디에도 없는 회원 수. 그사이 새 접수와 겨룰 수 있어 고치지 않고 알리기만 한다
     * @param malformed           읽을 수 없는 requests 값 수
     * @param requestsLeft        정리 대상을 지운 뒤 requests에 남을 요청 수(접수 중이라 남긴 REJECTED 기록 포함)
     * @param staleUnresolved     DB 결과도 보상 표식도 없는 이전 epoch 기록 수. 식에 세지 않고 남겨 두며 호출자가 알린다
     */
    public record Judgment(Mismatch mismatch, int quantity, long dbIssued, long inProgress, long pendingCompensation,
                           Long stock, long waitingRepublish, List<String> finishedRequestIds,
                           List<MissingUser> missingUsers, long extraUsers, SoldOutRepair soldOutRepair, int malformed,
                           long requestsLeft, long staleUnresolved) {

        public boolean matched() {
            return mismatch == null;
        }

        /**
         * key 만료를 늦춰 기록을 남겨야 하는가: DB 결과가 없는 접수 기록(진행 중·이전 epoch 미확정)이나 읽을 수 없는 기록이 있다. 대사는
         * 이런 쿠폰의 key 만료를 지금 + 여유까지 늦춰(줄이지 않는다) 발급 종료 + 여유에 복구 근거가 사라지지 않게 한다. 정리 규칙은 그대로다.
         */
        public boolean retainKeys() {
            return inProgress > 0 || staleUnresolved > 0 || malformed > 0;
        }

        /** 로그에 남기는 수량 식 */
        public String describe() {
            return "quantity=" + quantity + ", dbIssued=" + dbIssued + ", inProgress=" + inProgress
                    + ", pendingCompensation=" + pendingCompensation + ", stock=" + stock + ", malformed=" + malformed
                    + ", staleUnresolved=" + staleUnresolved;
        }
    }

    /**
     * users에 다시 넣을 회원.
     *
     * @param requestId 진행 중·보상 대기 요청이면 그 요청 ID(넣기 직전에 그 요청이 아직 보상되지 않았는지 저장소가 다시 본다). 확정 발급이면 null
     */
    public record MissingUser(long memberId, String requestId) {
    }

    public enum Mismatch {
        /** Redis 재고가 음수다 */
        NEGATIVE_STOCK,
        /** DB 발급 완료 수가 총수량을 넘었다 */
        DB_OVER_QUANTITY,
        /** 식이 맞지 않거나(원인 불명 차이) 재고·요청 기록을 읽을 수 없다 */
        UNEXPLAINED
    }

    public enum SoldOutRepair {
        NONE,
        /** 재고 0인데 매진 표시가 없다 */
        SET,
        /** 재고가 남았는데 매진 표시가 있다 */
        CLEAR
    }
}
