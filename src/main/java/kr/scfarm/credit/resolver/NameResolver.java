package kr.scfarm.credit.resolver;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * UUID ↔ 마크 닉네임 리졸버의 <b>Mojang HTTP 폴백 + 캐시</b> 계층.
 *
 * <p>중요: 이 클래스는 <b>Bukkit API 를 전혀 호출하지 않는다</b>(비동기 안전). 온라인/usercache 조회는
 * 메인 스레드에서 명령어 핸들러가 직접 수행하고, 그래도 못 찾을 때만 여기의 HTTP 메서드로 폴백한다.
 *
 * <p>레이트리밋/이름변경 대비로 결과는 짧은 TTL 캐시에 담는다. HTTP 는 JDK 내장 {@link HttpClient}.
 */
public final class NameResolver {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient http;
    private final Executor executor;
    private final ComponentLogger logger;

    private final ConcurrentHashMap<UUID, Cached<String>> nameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Cached<UUID>> uuidCache = new ConcurrentHashMap<>();

    public NameResolver(Executor executor, ComponentLogger logger) {
        this.executor = executor;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 캐시에 유효한 이름이 있으면 즉시 반환(메인 스레드에서 값 채우기용). */
    public Optional<String> peekName(UUID uuid) {
        Cached<String> c = nameCache.get(uuid);
        return (c != null && !c.expired()) ? Optional.ofNullable(c.value) : Optional.empty();
    }

    /** 명령어 핸들러가 Bukkit(online/usercache)로 확정한 이름을 캐시에 반영. */
    public void cacheName(UUID uuid, String name) {
        if (name != null) {
            nameCache.put(uuid, new Cached<>(name));
            uuidCache.put(name.toLowerCase(Locale.ROOT), new Cached<>(uuid));
        }
    }

    /** UUID → 닉네임 (Mojang 세션서버). 캐시 우선. */
    public CompletableFuture<Optional<String>> nameFromMojang(UUID uuid) {
        Cached<String> cached = nameCache.get(uuid);
        if (cached != null && !cached.expired()) {
            return CompletableFuture.completedFuture(Optional.ofNullable(cached.value));
        }
        String id = uuid.toString().replace("-", "");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200 && res.body() != null && !res.body().isBlank()) {
                    JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
                    if (obj.has("name")) {
                        String name = obj.get("name").getAsString();
                        cacheName(uuid, name);
                        return Optional.of(name);
                    }
                }
                // 204/404 등 = 프로필 없음
                nameCache.put(uuid, new Cached<>(null));
                return Optional.<String>empty();
            } catch (Exception e) {
                logger.warn("Mojang 이름 조회 실패: " + uuid, e);
                return Optional.<String>empty();
            }
        }, executor);
    }

    /** 닉네임 → UUID (Mojang API). 캐시 우선. */
    public CompletableFuture<Optional<UUID>> uuidFromMojang(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Cached<UUID> cached = uuidCache.get(key);
        if (cached != null && !cached.expired()) {
            return CompletableFuture.completedFuture(Optional.ofNullable(cached.value));
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200 && res.body() != null && !res.body().isBlank()) {
                    JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
                    if (obj.has("id")) {
                        UUID uuid = fromUndashed(obj.get("id").getAsString());
                        uuidCache.put(key, new Cached<>(uuid));
                        if (obj.has("name")) {
                            nameCache.put(uuid, new Cached<>(obj.get("name").getAsString()));
                        }
                        return Optional.of(uuid);
                    }
                }
                uuidCache.put(key, new Cached<>(null));
                return Optional.<UUID>empty();
            } catch (Exception e) {
                logger.warn("Mojang UUID 조회 실패: " + name, e);
                return Optional.<UUID>empty();
            }
        }, executor);
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
