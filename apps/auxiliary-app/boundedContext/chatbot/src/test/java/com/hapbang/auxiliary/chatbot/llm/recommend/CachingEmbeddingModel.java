package com.hapbang.auxiliary.chatbot.llm.recommend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 임베딩 캐시(#867). 실제 임베딩 모델 앞에 두고, 한 번 받은 벡터를 파일에 남겨 다음 실행부터는 모델을 부르지 않는다.
 * <p>
 * <b>왜 두나(가중치 보정 루프)</b>: 가중치는 "값 바꾸기 → 평가 → 기록"을 여러 번 돌려 정한다. 벡터는 가중치와 무관하므로 처음 한 번만
 * 임베딩 API를 부르고, 그 뒤 회차는 비용 없이 돈다. 캐시를 레포 밖({@link #defaultDir()})에 두는 것은 작업 폴더를 지운 뒤에도 같은 벡터로
 * 다시 재기 위해서다(후보 수를 바꾸는 변경 뒤 기준선을 다시 잴 때 숫자 차이가 벡터가 아니라 코드에서만 나오게).
 * <ul>
 *     <li>파일: 폴더 안 {@code <모델 이름>.json}. 모델·차원이 다르면 모델 이름이 달라 파일이 따로다.</li>
 *     <li>열쇠: {@code SHA-256(보낸 글 전체)}. 글(접두어 포함)이 바뀌면 열쇠가 달라져 그 글만 다시 임베딩한다.
 *     값은 float32 little-endian base64. 글·키·요청 헤더는 남기지 않는다.</li>
 *     <li>커밋하지 않는다(공개 레포, 공급자가 만든 값).</li>
 * </ul>
 */
public final class CachingEmbeddingModel implements EmbeddingModel {

    /** 캐시 폴더를 바꾸는 환경 변수 */
    public static final String CACHE_DIR_ENV = "RECOMMEND_EVAL_CACHE_DIR";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EmbeddingModel delegate;
    private final int dimensions;
    private final Path file;
    private final Map<String, String> entries;
    private int delegateTexts;

    /**
     * @param modelName 모델 이름(파일 이름). 공급자·모델·차원이 다르면 다른 이름을 준다
     */
    public CachingEmbeddingModel(EmbeddingModel delegate, String modelName, int dimensions, Path dir) {
        this.delegate = delegate;
        this.dimensions = dimensions;
        this.file = dir.resolve(modelName.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
        this.entries = load(file);
    }

    /** {@value #CACHE_DIR_ENV}가 있으면 그 폴더, 없으면 사용자 홈의 {@code .hapbang/recommend-eval-cache} */
    public static Path defaultDir() {
        String dir = System.getenv(CACHE_DIR_ENV);
        if (dir != null && !dir.isBlank()) {
            return Path.of(dir);
        }
        return Path.of(System.getProperty("user.home"), ".hapbang", "recommend-eval-cache");
    }

    /** 이 인스턴스가 실제 모델로 보낸 글 수 */
    public synchronized int delegateTexts() {
        return delegateTexts;
    }

    @Override
    public synchronized EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<String> keys = new ArrayList<>();
        List<String> misses = new ArrayList<>();
        for (String text : texts) {
            String key = key(text);
            keys.add(key);
            if (!entries.containsKey(key) && !misses.contains(text)) {
                misses.add(text);
            }
        }
        if (!misses.isEmpty()) {
            EmbeddingResponse response = delegate.call(new EmbeddingRequest(misses, request.getOptions()));
            List<Embedding> results = response.getResults();
            if (results.size() != misses.size()) {
                throw new IllegalStateException("임베딩 결과 수가 요청과 다릅니다 expected=" + misses.size() + ", actual=" + results.size());
            }
            for (int i = 0; i < misses.size(); i++) {
                entries.put(key(misses.get(i)), encode(results.get(i).getOutput()));
            }
            delegateTexts += misses.size();
            save();
        }
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            embeddings.add(new Embedding(decode(entries.get(keys.get(i))), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return call(new EmbeddingRequest(List.of(document.getText()), null)).getResult().getOutput();
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private static String key(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // 쓰다가 멈춰도 옛 파일이 남게 임시 파일에 쓰고 바꾼다
    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, JSON.writeValueAsString(entries));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("임베딩 캐시를 쓰지 못했습니다 " + file, e);
        }
    }

    private static Map<String, String> load(Path file) {
        if (!Files.exists(file)) {
            return new TreeMap<>();
        }
        try {
            return new TreeMap<>(JSON.readValue(Files.readString(file), new TypeReference<Map<String, String>>() {
            }));
        } catch (IOException e) {
            throw new UncheckedIOException("임베딩 캐시를 읽지 못했습니다 " + file, e);
        }
    }

    private static String encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private static float[] decode(String encoded) {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded)).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[buffer.remaining() / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
