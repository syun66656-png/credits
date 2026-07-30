package kr.scfarm.credit.resolver;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.scfarm.credit.db.CreditDao;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * UUID ↔ 마크 닉네임 리졸버의 <b>비동기 폴백 계층: 메모리 캐시 → DB 닉네임 캐시 → Mojang HTTP</b>.
 *
 * <p>PlayerPoints 의 {@code NameFetcher} 조회 사슬(온라인 → DB username_cache → OfflinePlayer → Mojang,
 * 결과는 항상 DB 캐시에 역기입 + 실패도 네거티브 캐싱)을 그대로 녹였다. DB 캐시 덕분에 멀티 백엔드
 * 네트워크에서 "다른 서버로만 접속했던" 유저도 Mojang 호출 없이 해석된다.
 * 단, PlayerPoints 가 아직 쓰는 폐기된 Mojang {@code /names} 엔드포인트는 답습하지 않고
 * 현행 sessionserver/profiles 엔드포인트를 쓴다.
 *
 * <p>중요: 이 클래스는 <b>Bukkit API 를 전혀 호출하지 않는다</b>(비동기 안전). 온라인/usercache 조회는
 * 메인 스레드에서 명령어 핸들러가 먼저 수행하고, 실패 시에만 여기로 폴백한다.
 */
public final class NameResolver {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient http;
    private final Executor executor;
    private final CreditDao dao;
    private final ComponentLogger logger;

    private final ConcurrentHashMap<UUID, Cached<String>> nameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Cached<UUID>> uuidCache = new ConcurrentHashMap<>();

    public NameResolver(Executor executor, CreditDao dao, ComponentLogger logger) {
        this.executor = executor;
        this.dao = dao;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 명령어 핸들러/리스너가 Bukkit(online/usercache)으로 확정한 이름을 메모리 캐시에 반영하고,
     * DB 닉네임 캐시에도 비동기 역기입한다(PlayerPoints 패턴).
     */
    public void cacheName(UUID uuid, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        nameCache.put(uuid, new Cached<>(name));
        uuidCache.put(name.toLowerCase(Locale.ROOT), new Cached<>(uuid));
        executor.execute(() -> {
            try {
                dao.upsertUsername(uuid, name);
            } catch (Exception e) {
                // 캐시 역기입 실패는 치명적이지 않음(다음 기회에 재기입)
            }
        });
    }

    /** UUID → 닉네임. 메모리 캐시 → DB 캐시 → Mojang(sessionserver) 순. 전 과정 비동기. */
    public CompletableFuture<Optional<String>> resolveNameOffline(UUID uuid) {
        Cached<String> cached = nameCache.get(uuid);
        if (cached != null && !cached.expired()) {
            return CompletableFuture.completedFuture(Optional.ofNullable(cached.value));
        }
        return CompletableFuture.supplyAsync(() -> {
            // 1) DB 닉네임 캐시
            try {
                String dbName = dao.lookupUsername(uuid);
                if (dbName != null) {
                    nameCache.put(uuid, new Cached<>(dbName));
                    uuidCache.put(dbName.toLowerCase(Locale.ROOT), new Cached<>(uuid));
                    return Optional.of(dbName);
                }
            } catch (Exception e) {
                logger.warn("DB 닉네임 캐시 조회 실패: " + uuid + " (" + e.getMessage() + ")");
            }
            // 2) Mojang 세션서버
            return fetchNameFromMojang(uuid);
        }, executor);
    }

    /** 닉네임 → UUID. 메모리 캐시 → DB 캐시 → Mojang API 순. 전 과정 비동기. */
    public CompletableFuture<Optional<UUID>> resolveUuidOffline(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Cached<UUID> cached = uuidCache.get(key);
        if (cached != null && !cached.expired()) {
            return CompletableFuture.completedFuture(Optional.ofNullable(cached.value));
        }
        return CompletableFuture.supplyAsync(() -> {
            // 1) DB 닉네임 캐시(대소문자 무시, 최근 갱신 우선 — 닉변 대비)
            try {
                UUID dbUuid = dao.lookupUuidByName(name);
                if (dbUuid != null) {
                    uuidCache.put(key, new Cached<>(dbUuid));
                    return Optional.of(dbUuid);
                }
            } catch (Exception e) {
                logger.warn("DB 닉네임 캐시 조회 실패: " + name + " (" + e.getMessage() + ")");
            }
            // 2) Mojang API
            return fetchUuidFromMojang(name, key);
        }, executor);
    }

    // ── Mojang HTTP (동기 — 반드시 executor 스레드에서만 호출) ───────────────

    private Optional<String> fetchNameFromMojang(UUID uuid) {
        String id = uuid.toString().replace("-", "");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200 && res.body() != null && !res.body().isBlank()) {
                JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
                if (obj.has("name")) {
                    String name = obj.get("name").getAsString();
                    cacheName(uuid, name); // 메모리 + DB 역기입
                    return Optional.of(name);
                }
            }
            // 프로필 없음(404/204/200-무필드)만 네거티브 캐싱한다. 429/5xx 를 캐싱하면 실존 유저가
            // 5분간 "없음"으로 굳어 인증/지급이 막힌다.
            int code = res.statusCode();
            if (code == 404 || code == 204 || code == 200) {
                nameCache.put(uuid, new Cached<>(null));
            } else {
                logger.warn("Mojang 이름 조회 일시 실패(HTTP " + code + "): " + uuid);
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.warn("Mojang 이름 조회 실패: " + uuid, e);
            return Optional.empty();
        }
    }

    private Optional<UUID> fetchUuidFromMojang(String name, String key) {
        // 반드시 인코딩한다. 예: "Notch#1" 을 그대로 붙이면 '#' 이 fragment 로 잘려 실제로는 "Notch" 를
        // 조회하고 → 전혀 다른 계정의 UUID 를 돌려준다(오지급 위험).
        String encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + encoded))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200 && res.body() != null && !res.body().isBlank()) {
                JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
                if (obj.has("id")) {
                    UUID uuid = fromUndashed(obj.get("id").getAsString());
                    uuidCache.put(key, new Cached<>(uuid));
                    if (obj.has("name")) {
                        cacheName(uuid, obj.get("name").getAsString()); // DB 역기입 포함
                    }
                    return Optional.of(uuid);
                }
            }
            // 404/204(= 없는 이름)만 네거티브 캐싱한다. 429(레이트리밋)/5xx 를 캐싱하면
            // 실존 플레이어가 5분 동안 "없음"으로 굳어 지급이 막힌다.
            int code = res.statusCode();
            if (code == 404 || code == 204 || code == 200) {
                uuidCache.put(key, new Cached<>(null));
            } else {
                logger.warn("Mojang UUID 조회 일시 실패(HTTP " + code + "): " + name);
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.warn("Mojang UUID 조회 실패: " + name, e);
            return Optional.empty();
        }
    }

    private static UUID fromUndashed(String id) {
        String dashed = id.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                "$1-$2-$3-$4-$5");
        return UUID.fromString(dashed);
    }

    private static final class Cached<T> {
        final T value;
        final long expiresAt;

        Cached(T value) {
            this.value = value;
            this.expiresAt = System.currentTimeMillis() + TTL.toMillis();
        }

        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
