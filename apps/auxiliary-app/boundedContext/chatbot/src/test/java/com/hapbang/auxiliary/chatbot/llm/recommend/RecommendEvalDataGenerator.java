package com.hapbang.auxiliary.chatbot.llm.recommend;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Activity;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Answer;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Appearance;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.AppearanceReview;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Field;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Influencer;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Lecture;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Member;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Rating;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Split;

/**
 * 추천 평가 더미 데이터 생성기(#867). 같은 시드면 같은 데이터다(난수는 {@link Random} 하나를 정해진 순서로 쓴다).
 * 규칙을 바꾸면 커밋한 파일과 달라지므로 {@code RecommendEvalDataGeneratorTest}의 안내대로 파일을 다시 만든다.
 * <p>생성 규칙:
 * <ul>
 *     <li>분야 8개. 분야마다 강의 15개, 판매자 3명(한 명이 5개). 강의 글은 분야별 제목·소개 문장·콘텐츠 제목 묶음에서 고른다.
 *     분야 안 인기 순위는 섞어서 정하고, 고를 때 {@code 1/√순위}에 비례한다(인기가 한쪽으로 크게 쏠리지 않게).</li>
 *     <li>강의 회원 100명: 취향 분야 1개(60%) 또는 2개. 구매 3~8개, 장바구니 0~4개. 신호마다 80%는 취향 분야, 20%는 취향 밖 분야(잡음).
 *     한 회원이 같은 강의를 두 번 고르지 않는다. 시각은 기준일부터 90일 안.</li>
 *     <li>강의 리뷰: 구매의 절반쯤. 취향 강의는 대부분 4~5점, 잡음 강의는 대부분 1~3점(별점 보정값이 의미를 갖게).</li>
 *     <li>관심사 문장: 회원마다 취향 분야 하나의 문장.</li>
 *     <li>인플루언서 100명: 분야 1개(60%) 또는 2개, 자기소개는 분야마다 분야 문장 하나 + 분야와 무관한 방송 방식 문장 하나. 완료 출연은 한 명당 목표 2~6건(상대 쪽에서 더 생길 수 있고
 *     한 명당 최대 8명). 상대는 75%가 분야가 겹치는 인플루언서, 25%는 아무나. 출연마다 서로의 리뷰를 60% 확률로 남기고,
 *     별점은 인플루언서마다 정한 품질을 따른다.</li>
 *     <li>정답: 강의 회원마다 구매 하나, 인플루언서마다 함께 완료한 상대 하나. 숨긴 뒤에도 양쪽에 상대가 하나 이상 남게 고른다.</li>
 *     <li>80:20: 섞은 순서의 앞 80명이 조정용, 나머지 20명이 확인용. 강의 회원과 인플루언서를 따로 나눈다.</li>
 * </ul>
 */
final class RecommendEvalDataGenerator {

    static final long SEED = 867;

    static final int MEMBERS = 100;
    static final int INFLUENCERS = 100;
    static final int LECTURES_PER_FIELD = 15;
    static final int SELLERS_PER_FIELD = 3;
    static final int TUNING = 80;
    /** 신호가 취향 분야에서 나올 확률(나머지는 잡음) */
    static final double TASTE_RATIO = 0.8;
    /** 출연 상대가 분야가 겹치는 인플루언서일 확률 */
    static final double SAME_FIELD_PARTNER = 0.75;

    static final long FIRST_SELLER_ID = 1001;
    static final long FIRST_INFLUENCER_ID = 2001;

    private static final int MAX_PARTNERS = 8;
    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final int MINUTES = 90 * 24 * 60;

    /** 분야와 무관한 방송 방식 문장. 같은 분야 인플루언서의 자기소개가 똑같아지지 않게 하나씩 붙인다 */
    private static final List<String> STYLES = List.of("주로 평일 저녁에 방송합니다.", "주말 오후에 길게 방송합니다.",
            "짧은 영상과 라이브를 함께 올립니다.", "시청자 질문에 답하는 시간을 꼭 둡니다.", "편하게 이야기하는 분위기를 좋아합니다.",
            "새벽 시간대 방송을 자주 합니다.", "초보 시청자도 이해하기 쉽게 설명합니다.", "다른 크리에이터와 협업을 자주 합니다.",
            "방송 일정은 채널 공지로 알립니다.", "꾸준히 매일 방송하는 것이 목표입니다.");

    private final Random random;

    private RecommendEvalDataGenerator(long seed) {
        this.random = new Random(seed);
    }

    static RecommendEvalData generate(long seed) {
        return new RecommendEvalDataGenerator(seed).build(seed);
    }

    private RecommendEvalData build(long seed) {
        List<Field> fields = new ArrayList<>();
        for (FieldText text : FieldText.ALL) {
            fields.add(new Field(text.code(), text.name()));
        }

        // 강의와 분야 안 인기 가중
        List<Long> sellerIds = new ArrayList<>();
        List<Lecture> lectures = new ArrayList<>();
        Map<String, List<Lecture>> lecturesByField = new HashMap<>();
        Map<Long, Double> popularity = new HashMap<>();
        for (int f = 0; f < FieldText.ALL.size(); f++) {
            FieldText text = FieldText.ALL.get(f);
            for (int s = 0; s < SELLERS_PER_FIELD; s++) {
                sellerIds.add(FIRST_SELLER_ID + (long) f * SELLERS_PER_FIELD + s);
            }
            List<Integer> ranks = shuffledRange(LECTURES_PER_FIELD);
            List<Lecture> inField = new ArrayList<>();
            for (int i = 0; i < LECTURES_PER_FIELD; i++) {
                long id = (long) f * LECTURES_PER_FIELD + i + 1;
                long sellerId = FIRST_SELLER_ID + (long) f * SELLERS_PER_FIELD + i % SELLERS_PER_FIELD;
                String description = String.join(" ", pickDistinct(text.descriptions(), 2));
                Lecture lecture = new Lecture(id, sellerId, text.code(), text.titles().get(i), description,
                        pickDistinct(text.contents(), 3));
                inField.add(lecture);
                popularity.put(id, 1 / Math.sqrt(ranks.get(i) + 1));
            }
            lectures.addAll(inField);
            lecturesByField.put(text.code(), inField);
        }

        // 강의 회원과 신호
        List<Member> members = new ArrayList<>();
        List<Activity> purchases = new ArrayList<>();
        List<Activity> carts = new ArrayList<>();
        List<Rating> reviews = new ArrayList<>();
        List<Answer> lectureAnswers = new ArrayList<>();
        for (long memberId = 1; memberId <= MEMBERS; memberId++) {
            List<String> taste = pickDistinct(codes(fields), random.nextDouble() < 0.6 ? 1 : 2);
            FieldText interestField = FieldText.of(taste.get(random.nextInt(taste.size())));
            members.add(new Member(memberId, taste, interestField.interests().get(random.nextInt(interestField.interests().size()))));

            Set<Long> used = new HashSet<>();
            int purchaseCount = 3 + random.nextInt(6);
            List<Activity> mine = new ArrayList<>();
            for (int i = 0; i < purchaseCount; i++) {
                Activity purchase = activity(purchases.size() + 1, memberId, taste, fields, lecturesByField, popularity, used);
                purchases.add(purchase);
                mine.add(purchase);
            }
            int cartCount = random.nextInt(5);
            for (int i = 0; i < cartCount; i++) {
                carts.add(activity(carts.size() + 1, memberId, taste, fields, lecturesByField, popularity, used));
            }
            for (Activity purchase : mine) {
                if (random.nextDouble() < 0.5) {
                    reviews.add(new Rating(reviews.size() + 1, memberId, purchase.lectureId(), rating(purchase.taste())));
                }
            }
            lectureAnswers.add(new Answer(memberId, mine.get(random.nextInt(mine.size())).lectureId()));
        }

        // 인플루언서와 출연
        List<Influencer> influencers = new ArrayList<>();
        Map<Long, Double> quality = new HashMap<>();
        for (int i = 0; i < INFLUENCERS; i++) {
            long id = FIRST_INFLUENCER_ID + i;
            List<String> own = pickDistinct(codes(fields), random.nextDouble() < 0.6 ? 1 : 2);
            List<String> bio = new ArrayList<>();
            for (String code : own) {
                List<String> bios = FieldText.of(code).bios();
                bio.add(bios.get(random.nextInt(bios.size())));
            }
            bio.add(STYLES.get(random.nextInt(STYLES.size())));
            influencers.add(new Influencer(id, own, String.join(" ", bio)));
            quality.put(id, random.nextDouble());
        }
        Map<Long, Set<Long>> partners = new HashMap<>();
        for (Influencer influencer : influencers) {
            partners.put(influencer.id(), new TreeSet<>());
        }
        List<Appearance> appearances = new ArrayList<>();
        for (Influencer influencer : influencers) {
            int target = 2 + random.nextInt(5);
            while (partners.get(influencer.id()).size() < target) {
                Long partner = pickPartner(influencer, influencers, partners);
                if (partner == null) {
                    break;
                }
                partners.get(influencer.id()).add(partner);
                partners.get(partner).add(influencer.id());
                boolean host = random.nextBoolean();
                appearances.add(new Appearance(appearances.size() + 1, host ? influencer.id() : partner,
                        host ? partner : influencer.id()));
            }
        }
        List<AppearanceReview> appearanceReviews = new ArrayList<>();
        for (Appearance appearance : appearances) {
            for (long reviewee : List.of(appearance.hostId(), appearance.guestId())) {
                if (random.nextDouble() < 0.6) {
                    appearanceReviews.add(new AppearanceReview(appearanceReviews.size() + 1, appearance.id(), reviewee,
                            influencerRating(quality.get(reviewee))));
                }
            }
        }
        List<Answer> influencerAnswers = influencerAnswers(influencers, partners);

        Split lectureSplit = split(members.stream().map(Member::id).toList());
        Split influencerSplit = split(influencers.stream().map(Influencer::id).toList());
        return new RecommendEvalData(seed, fields, sellerIds, lectures, members, purchases, carts, reviews, influencers,
                appearances, appearanceReviews, lectureAnswers, influencerAnswers, lectureSplit, influencerSplit);
    }

    // 신호 하나: 취향(80%) 또는 취향 밖 분야에서, 그 회원이 아직 고르지 않은 강의를 인기 가중으로
    private Activity activity(long id, long memberId, List<String> taste, List<Field> fields,
            Map<String, List<Lecture>> lecturesByField, Map<Long, Double> popularity, Set<Long> used) {
        boolean tasteSignal = random.nextDouble() < TASTE_RATIO;
        List<String> pool = new ArrayList<>();
        for (String code : codes(fields)) {
            if (taste.contains(code) == tasteSignal) {
                pool.add(code);
            }
        }
        String field = pool.get(random.nextInt(pool.size()));
        long lectureId = weightedPick(lecturesByField.get(field), popularity, used);
        used.add(lectureId);
        LocalDateTime at = START.plusMinutes(random.nextInt(MINUTES));
        return new Activity(id, memberId, lectureId, tasteSignal, at);
    }

    private long weightedPick(List<Lecture> lectures, Map<Long, Double> popularity, Set<Long> used) {
        double total = 0;
        for (Lecture lecture : lectures) {
            if (!used.contains(lecture.id())) {
                total += popularity.get(lecture.id());
            }
        }
        double point = random.nextDouble() * total;
        long last = -1;
        for (Lecture lecture : lectures) {
            if (used.contains(lecture.id())) {
                continue;
            }
            last = lecture.id();
            point -= popularity.get(lecture.id());
            if (point < 0) {
                return lecture.id();
            }
        }
        return last;
    }

    // 취향 강의는 대부분 4~5점, 잡음 강의는 대부분 1~3점
    private int rating(boolean taste) {
        if (taste) {
            return random.nextDouble() < 0.8 ? 4 + random.nextInt(2) : 3;
        }
        return random.nextDouble() < 0.8 ? 1 + random.nextInt(3) : 4;
    }

    // 품질 0~1을 2.5~5점 근처로, ±1 흔들어 1~5점
    private int influencerRating(double quality) {
        long rating = Math.round(2.5 + 2.5 * quality) + random.nextInt(3) - 1;
        return (int) Math.max(1, Math.min(5, rating));
    }

    // 75%는 분야가 겹치는 인플루언서, 아니면 아무나. 상대가 꽉 찼으면 다른 쪽에서 찾는다
    private Long pickPartner(Influencer influencer, List<Influencer> influencers, Map<Long, Set<Long>> partners) {
        boolean sameField = random.nextDouble() < SAME_FIELD_PARTNER;
        List<Long> preferred = new ArrayList<>();
        List<Long> others = new ArrayList<>();
        for (Influencer other : influencers) {
            if (other.id() == influencer.id() || partners.get(influencer.id()).contains(other.id())
                    || partners.get(other.id()).size() >= MAX_PARTNERS) {
                continue;
            }
            if (!sameField || !Collections.disjoint(influencer.fields(), other.fields())) {
                preferred.add(other.id());
            } else {
                others.add(other.id());
            }
        }
        List<Long> pool = preferred.isEmpty() ? others : preferred;
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    // 인플루언서마다 상대 하나. 이미 숨긴 쌍이면 더 잃는 것이 없다. 아니면 양쪽 모두 숨긴 뒤 상대가 하나 이상 남는 쌍만 고른다
    private List<Answer> influencerAnswers(List<Influencer> influencers, Map<Long, Set<Long>> partners) {
        Map<Long, Integer> remaining = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : partners.entrySet()) {
            remaining.put(entry.getKey(), entry.getValue().size());
        }
        Set<String> hidden = new HashSet<>();
        List<Answer> answers = new ArrayList<>();
        for (Influencer influencer : influencers) {
            long id = influencer.id();
            List<Long> candidates = new ArrayList<>();
            for (long partner : partners.get(id)) {
                if (hidden.contains(pairKey(id, partner)) || remaining.get(id) >= 2 && remaining.get(partner) >= 2) {
                    candidates.add(partner);
                }
            }
            if (candidates.isEmpty()) {
                throw new IllegalStateException("숨길 상대를 고를 수 없습니다 influencer=" + id + ". 생성 규칙이나 시드를 바꾼다");
            }
            long answer = candidates.get(random.nextInt(candidates.size()));
            if (hidden.add(pairKey(id, answer))) {
                remaining.merge(id, -1, Integer::sum);
                remaining.merge(answer, -1, Integer::sum);
            }
            answers.add(new Answer(id, answer));
        }
        return answers;
    }

    static String pairKey(long a, long b) {
        return Math.min(a, b) + "-" + Math.max(a, b);
    }

    private Split split(List<Long> ids) {
        List<Long> shuffled = new ArrayList<>(ids);
        Collections.shuffle(shuffled, random);
        List<Long> tuning = new ArrayList<>(shuffled.subList(0, TUNING));
        List<Long> holdout = new ArrayList<>(shuffled.subList(TUNING, shuffled.size()));
        Collections.sort(tuning);
        Collections.sort(holdout);
        return new Split(tuning, holdout);
    }

    private List<Integer> shuffledRange(int size) {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            values.add(i);
        }
        Collections.shuffle(values, random);
        return values;
    }

    private <T> List<T> pickDistinct(List<T> values, int count) {
        List<T> copy = new ArrayList<>(values);
        Collections.shuffle(copy, random);
        return new ArrayList<>(copy.subList(0, count));
    }

    private static List<String> codes(List<Field> fields) {
        List<String> codes = new ArrayList<>();
        for (Field field : fields) {
            codes.add(field.code());
        }
        return codes;
    }

    /**
     * 분야별 글 묶음. 모두 지어낸 문장이다.
     *
     * @param titles       강의 제목 15개
     * @param descriptions 강의 소개 문장(강의마다 2개를 고른다)
     * @param contents     콘텐츠 제목(강의마다 3개를 고른다)
     * @param bios         인플루언서 자기소개 문장
     * @param interests    관심사 문장
     */
    record FieldText(
            String code,
            String name,
            List<String> titles,
            List<String> descriptions,
            List<String> contents,
            List<String> bios,
            List<String> interests
    ) {

        static FieldText of(String code) {
            for (FieldText text : ALL) {
                if (text.code().equals(code)) {
                    return text;
                }
            }
            throw new IllegalArgumentException(code);
        }

        static final List<FieldText> ALL = List.of(
                new FieldText("GAME", "게임",
                        List.of("롤 랭크 올리는 라인전 기초", "FPS 에임 연습 루틴", "발로란트 요원별 운영 전략", "스타크래프트 빌드 오더 입문",
                                "배틀그라운드 생존 운영", "게임 방송 화면 구성과 OBS 설정", "모바일 게임 공략 영상 만들기", "인디 게임 리뷰 콘텐츠 기획",
                                "격투 게임 콤보 연습법", "마인크래프트 건축 기초", "게임 실황 말하기 연습", "스피드런 루트 짜기",
                                "카드 게임 덱 구성 이론", "MMORPG 레이드 공략 정리", "게임 하이라이트 편집 실전"),
                        List.of("게임 실력을 꾸준히 올리고 싶은 분을 위한 강의입니다.", "실제 플레이 장면을 보며 판단 기준을 익힙니다.",
                                "랭크 게임에서 바로 써먹을 수 있는 내용을 담았습니다.", "게임 방송을 시작하려는 스트리머에게도 도움이 됩니다.",
                                "패치 버전에 흔들리지 않는 기본기를 다룹니다.", "초보부터 중수까지 단계별로 연습합니다."),
                        List.of("라인전 기본 개념", "미니맵 보는 습관", "에임 감도 맞추기", "팀 교전 포지션", "리플레이 분석하기",
                                "빌드와 아이템 선택", "실수 줄이는 루틴", "방송 오버레이 만들기", "하이라이트 장면 고르기", "멘탈 관리와 휴식"),
                        List.of("게임 실황과 랭크 공략을 주로 방송합니다.", "FPS와 전략 게임을 함께 플레이하는 게임 스트리머입니다.",
                                "신작 게임 리뷰와 첫인상 방송을 합니다.", "시청자와 함께하는 게임 합방을 좋아합니다.",
                                "e스포츠 경기 해설과 분석 방송을 합니다."),
                        List.of("랭크 게임 실력을 올리고 싶어요", "게임 방송을 시작해 보고 싶어요", "FPS 에임을 연습하고 싶어요",
                                "전략 게임 운영을 배우고 싶어요")),
                new FieldText("MUSIC", "음악",
                        List.of("보컬 호흡과 발성 기초", "통기타 코드 반주 입문", "작곡을 위한 화성학 첫걸음", "미디로 비트 만들기",
                                "피아노 반주 패턴 익히기", "노래 커버 영상 녹음하기", "랩 가사 쓰기와 플로우", "드럼 기본 리듬 연습",
                                "믹싱과 마스터링 기초", "음악 방송용 마이크 세팅", "우쿨렐레로 배우는 동요 반주", "작사 수업: 이야기 만들기",
                                "베이스 기타 라인 만들기", "버스킹 무대 준비하기", "음정 교정 연습법"),
                        List.of("노래와 연주를 좋아하는 분을 위한 음악 강의입니다.", "직접 소리를 내며 따라 하는 실습 위주로 진행합니다.",
                                "악보를 몰라도 시작할 수 있게 구성했습니다.", "음악 방송과 커버 영상에 바로 쓰는 팁을 담았습니다.",
                                "곡 하나를 완성하는 과정을 처음부터 끝까지 봅니다.", "귀로 듣고 따라 하는 감각을 기릅니다."),
                        List.of("복식 호흡 연습", "기본 코드 잡기", "메트로놈 박자 연습", "멜로디 만들기", "가사 붙이기", "녹음 환경 만들기",
                                "오디오 인터페이스 연결", "EQ와 컴프레서", "곡 구성 이해하기", "무대 매너"),
                        List.of("노래 커버와 라이브 방송을 하는 보컬리스트입니다.", "기타 연주와 작곡 과정을 방송합니다.",
                                "시청자 신청곡을 피아노로 연주합니다.", "힙합 비트 메이킹과 랩 방송을 합니다.",
                                "음악 합방에서 반주와 화음을 맡는 것을 좋아합니다."),
                        List.of("노래를 더 잘 부르고 싶어요", "기타 반주를 배우고 싶어요", "직접 곡을 만들어 보고 싶어요",
                                "음악 방송 장비를 갖추고 싶어요")),
                new FieldText("COOKING", "요리",
                        List.of("자취생 한 그릇 요리", "칼질과 재료 손질 기초", "집에서 만드는 한식 반찬", "홈베이킹 쿠키와 머핀",
                                "파스타 소스 세 가지", "다이어트 도시락 만들기", "요리 방송 촬영 구도 잡기", "제빵 반죽과 발효 이해하기",
                                "중식 볶음 요리 불 조절", "디저트 플레이팅 연습", "비건 집밥 레시피", "김치와 장아찌 담그기",
                                "브런치 메뉴 만들기", "에어프라이어 활용 요리", "일식 덮밥과 국물 요리"),
                        List.of("집에서 맛있는 요리를 만들고 싶은 분을 위한 강의입니다.", "구하기 쉬운 재료로 따라 할 수 있게 구성했습니다.",
                                "요리 순서와 불 조절을 자세히 보여줍니다.", "먹방과 요리 방송에 어울리는 메뉴를 담았습니다.",
                                "실패하지 않는 계량과 손질 방법을 다룹니다.", "한 번 배워 두면 계속 응용할 수 있는 기본 레시피입니다."),
                        List.of("재료 손질하기", "양념 비율 맞추기", "불 조절 연습", "밑반찬 보관법", "반죽 만들기", "오븐 온도 이해하기",
                                "그릇에 담는 법", "장보기 목록 짜기", "칼 관리와 안전", "요리 촬영 조명"),
                        List.of("집밥 요리와 먹방을 함께 방송합니다.", "베이킹 과정을 처음부터 끝까지 보여주는 방송을 합니다.",
                                "자취 요리와 간단 레시피를 소개합니다.", "세계 요리를 직접 만들어 보는 요리 크리에이터입니다.",
                                "요리 합방에서 시청자 메뉴 추천을 받아 만듭니다."),
                        List.of("간단한 집밥을 만들고 싶어요", "베이킹을 배워 보고 싶어요", "요리 방송을 해 보고 싶어요",
                                "건강한 도시락을 싸고 싶어요")),
                new FieldText("FITNESS", "운동",
                        List.of("맨몸 근력 운동 입문", "홈트레이닝 30일 루틴", "스쿼트와 데드리프트 자세 교정", "러닝 호흡과 페이스 조절",
                                "필라테스 코어 강화", "요가로 하는 아침 스트레칭", "체지방 감량 식단과 운동", "턱걸이 처음 성공하기",
                                "거북목과 허리 통증 운동", "케틀벨 전신 운동", "마라톤 완주 준비", "운동 방송 진행과 시청자 참여",
                                "벌크업 운동 프로그램", "폼롤러 회복 운동", "등산 체력 기르기"),
                        List.of("건강한 몸을 만들고 싶은 분을 위한 운동 강의입니다.", "부상 없이 따라 할 수 있도록 자세를 먼저 잡습니다.",
                                "집에서도 장비 없이 할 수 있는 동작이 많습니다.", "운동 방송에서 함께 하기 좋은 루틴을 담았습니다.",
                                "주차별 계획으로 꾸준히 할 수 있게 구성했습니다.", "운동과 식단을 함께 관리하는 방법을 다룹니다."),
                        List.of("준비 운동", "바른 자세 잡기", "세트와 반복 횟수", "코어 운동", "하체 운동", "상체 운동", "유산소 구간",
                                "마무리 스트레칭", "식단 기록하기", "회복과 휴식"),
                        List.of("홈트레이닝을 시청자와 함께 하는 운동 방송을 합니다.", "헬스 트레이너로 자세 교정 방송을 합니다.",
                                "러닝 기록과 마라톤 준비 과정을 방송합니다.", "요가와 필라테스 수업을 라이브로 진행합니다.",
                                "운동 합방에서 함께 챌린지를 하는 것을 좋아합니다."),
                        List.of("집에서 운동을 시작하고 싶어요", "살을 빼고 체력을 기르고 싶어요", "운동 자세를 바로잡고 싶어요",
                                "러닝을 꾸준히 하고 싶어요")),
                new FieldText("BEAUTY", "뷰티",
                        List.of("데일리 메이크업 기초", "피부 타입별 스킨케어 루틴", "눈매를 살리는 아이 메이크업", "퍼스널 컬러 찾기",
                                "남자 그루밍 입문", "헤어 스타일링과 고데기 사용법", "뷰티 방송 조명과 카메라 세팅", "화장품 성분 읽는 법",
                                "네일 아트 기초", "여드름 피부 관리", "웨딩 하객 메이크업", "향수 고르는 법", "눈썹 정리와 그리기",
                                "커버 메이크업 테크닉", "뷰티 리뷰 영상 만들기"),
                        List.of("자신에게 맞는 꾸밈을 찾고 싶은 분을 위한 뷰티 강의입니다.", "실제 얼굴에 적용하는 과정을 단계별로 보여줍니다.",
                                "제품 선택부터 사용 순서까지 함께 다룹니다.", "뷰티 방송과 리뷰 영상에 바로 쓸 수 있는 팁을 담았습니다.",
                                "피부 고민별로 관리 방법을 나누어 설명합니다.", "적은 제품으로도 할 수 있는 방법을 소개합니다."),
                        List.of("피부 바탕 정리", "베이스 바르기", "색조 고르기", "브러시 사용법", "클렌징 순서", "제품 성분표 보기",
                                "조명과 각도", "헤어 드라이 기본", "손톱 정리", "제품 리뷰 구성"),
                        List.of("메이크업 튜토리얼과 제품 리뷰를 방송합니다.", "스킨케어 루틴과 피부 관리 방송을 합니다.",
                                "헤어 스타일링을 보여주는 뷰티 크리에이터입니다.", "시청자 퍼스널 컬러 상담 방송을 합니다.",
                                "뷰티 합방에서 서로 메이크업을 해 주는 콘텐츠를 합니다."),
                        List.of("기초 화장법을 배우고 싶어요", "피부 관리를 제대로 하고 싶어요", "나에게 맞는 색을 찾고 싶어요",
                                "뷰티 리뷰 영상을 찍고 싶어요")),
                new FieldText("TRAVEL", "여행",
                        List.of("혼자 떠나는 국내 여행 계획", "저예산 해외 배낭여행", "여행 브이로그 촬영과 편집", "일본 소도시 여행 가이드",
                                "캠핑 장비와 첫 캠핑", "여행 사진 구도 잡기", "유럽 기차 여행 루트 짜기", "제주도 일주 코스",
                                "여행 영어 생활 회화", "항공권과 숙소 싸게 예약하기", "동남아 휴양지 여행", "차박 여행 준비",
                                "여행 라이브 방송 진행", "가족 여행 일정 짜기", "트레킹 여행 준비물"),
                        List.of("새로운 곳으로 떠나고 싶은 분을 위한 여행 강의입니다.", "일정과 예산을 직접 짜 보는 실습이 들어 있습니다.",
                                "현지에서 바로 쓸 수 있는 정보를 담았습니다.", "여행 방송과 브이로그를 찍는 방법도 함께 다룹니다.",
                                "처음 떠나는 사람도 불안하지 않게 준비 순서를 알려줍니다.", "실제 여행 기록을 예로 들어 설명합니다."),
                        List.of("여행지 고르기", "예산 짜기", "교통편 예약", "숙소 고르기", "짐 싸기", "현지 맛집 찾기", "사진 찍는 법",
                                "영상 편집 흐름", "안전 수칙", "여행 기록 남기기"),
                        List.of("국내외 여행 브이로그를 올리고 여행 라이브를 합니다.", "캠핑과 차박 여행을 방송합니다.",
                                "저예산 배낭여행 경험을 나누는 여행 크리에이터입니다.", "현지에서 시청자와 소통하는 여행 방송을 합니다.",
                                "여행 합방에서 함께 일정을 짜는 콘텐츠를 합니다."),
                        List.of("혼자 여행을 떠나 보고 싶어요", "여행 브이로그를 찍고 싶어요", "싸게 해외여행을 가고 싶어요",
                                "캠핑을 시작해 보고 싶어요")),
                new FieldText("CODING", "프로그래밍",
                        List.of("파이썬 첫걸음", "자바 객체지향 기초", "웹 개발 HTML과 CSS 입문", "자바스크립트로 만드는 웹 화면",
                                "스프링 부트 REST API 만들기", "SQL 데이터 조회 기초", "깃과 깃허브 협업", "알고리즘 문제 풀이 입문",
                                "리액트 컴포넌트 기초", "파이썬 데이터 분석", "코딩 라이브 방송 진행법", "도커로 개발 환경 만들기",
                                "안드로이드 앱 만들기", "자동화 스크립트 작성", "머신러닝 개념 이해"),
                        List.of("개발을 처음 배우는 분을 위한 프로그래밍 강의입니다.", "코드를 직접 따라 치며 작은 프로젝트를 완성합니다.",
                                "개념을 그림과 예제로 쉽게 설명합니다.", "코딩 방송이나 개발 영상을 만들 때도 도움이 됩니다.",
                                "실무에서 자주 쓰는 도구를 함께 익힙니다.", "오류를 읽고 고치는 방법까지 다룹니다."),
                        List.of("개발 환경 설치", "변수와 자료형", "조건문과 반복문", "함수 만들기", "클래스와 객체", "데이터베이스 연결",
                                "API 호출하기", "테스트 작성하기", "버전 관리", "배포하기"),
                        List.of("코딩 라이브로 작은 프로젝트를 만드는 개발 방송을 합니다.", "알고리즘 문제를 시청자와 함께 푸는 방송을 합니다.",
                                "웹 개발 과정을 보여주는 개발자 크리에이터입니다.", "개발 공부 방법과 취업 이야기를 나눕니다.",
                                "코딩 합방에서 서로 코드 리뷰를 하는 콘텐츠를 합니다."),
                        List.of("코딩을 처음 배워 보고 싶어요", "웹 서비스를 직접 만들고 싶어요", "알고리즘 문제를 잘 풀고 싶어요",
                                "데이터 분석을 배우고 싶어요")),
                new FieldText("FINANCE", "재테크",
                        List.of("사회초년생 돈 관리", "주식 투자 첫걸음", "ETF로 시작하는 장기 투자", "가계부 쓰는 습관", "부동산 기초 지식",
                                "연말정산과 세금 아끼기", "신용카드와 대출 이해하기", "경제 뉴스 읽는 법", "배당주 투자 전략",
                                "비상금과 저축 계획", "연금과 노후 준비", "재테크 방송 콘텐츠 만들기", "환율과 해외 투자", "부업 수입 관리",
                                "보험 고르는 기준"),
                        List.of("돈을 잘 모으고 불리고 싶은 분을 위한 재테크 강의입니다.", "실제 숫자로 계산해 보며 이해합니다.",
                                "어려운 금융 용어를 쉬운 말로 풀어 설명합니다.", "재테크 방송에서 다룰 만한 주제를 함께 정리합니다.",
                                "위험을 줄이는 기본 원칙을 먼저 배웁니다.", "월급을 기준으로 나만의 계획을 세워 봅니다."),
                        List.of("수입과 지출 정리", "목표 금액 정하기", "투자 계좌 만들기", "분산 투자", "수익률 계산", "세금 기초",
                                "대출 이자 계산", "경제 지표 보기", "자동 저축 설정", "투자 기록 남기기"),
                        List.of("주식과 경제 뉴스를 쉽게 풀어주는 재테크 방송을 합니다.", "사회초년생 돈 관리 상담 방송을 합니다.",
                                "부동산과 절세 정보를 나누는 재테크 크리에이터입니다.", "투자 일지를 공개하며 시청자와 이야기합니다.",
                                "재테크 합방에서 서로 포트폴리오를 비교하는 콘텐츠를 합니다."),
                        List.of("월급 관리를 잘하고 싶어요", "주식 투자를 시작하고 싶어요", "세금을 아끼는 법을 알고 싶어요",
                                "노후 준비를 하고 싶어요")));
    }
}
