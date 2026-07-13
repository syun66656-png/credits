package kr.scfarm.credit.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.scfarm.credit.db.ChargeResult;
import kr.scfarm.credit.db.CreditDao;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * 홈페이지 결제 브릿지(명세 8번).
 *
 * <p>주기적으로 대기 결제를 폴링해 "정확히 한 번" 지급한다. 지급은 {@link CreditDao#processCharge}
 * 단일 트랜잭션(processed_charge PK 로 멱등)을 사용하므로, 보고(POST complete)가 실패해 같은 건이
 * 다시 내려와도 재지급되지 않고 보고만 재시도되어 결국 수렴한다.
 *
 * <p>오프라인/미접속 UUID 도 지급된다(UUID 기반 DB UPSERT — {@code Bukkit.getPlayer} 를 요구하지 않는다).
 * 이 클래스는 Bukkit API 를 호출하지 않으며, 폴링은 비동기 스케줄러 스레드에서 실행된다.
 *
 * <p>명세 7번(제재 동기화)은 이번 범위 밖. 브릿지의 HTTP/헤더 구조를 재사용할 수 있게만 두었다.
 */
public final class HomepageBridge {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    private final String pendingUrl;
    private final String completeUrl;
    private final String pluginKey;
    private final CreditDao dao;
    private final ComponentLogger logger;
    private final HttpClient http;

    public HomepageBridge(String baseUrl, String pluginKey, CreditDao dao, ComponentLogger logger) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.pendingUrl = base + "/api/plugin/charges/pending";
        this.completeUrl = base + "/api/plugin/charges/complete";
        this.pluginKey = pluginKey;
        this.dao = dao;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 폴링 1회. 비동기 스케줄러 스레드에서 호출된다(HTTP/DB 블로킹 허용). 어떤 예외도 밖으로 던지지 않는다.
     */
    public void pollOnce() {
        String body;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(pendingUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("x-plugin-key", pluginKey)
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401) {
                logger.error("홈페이지 브릿지 인증 실패(401): plugin-key 를 확인하세요.");
                return;
            }
            if (res.statusCode() != 200) {
                logger.warn("홈페이지 대기결제 조회 실패(HTTP " + res.statusCode() + "). 다음 폴링에 재시도합니다.");
                return;
            }
            body = res.body();
        } catch (Exception e) {
            // 네트워크 오류/타임아웃 → 다음 폴링에 재시도(크래시 금지)
            logger.warn("홈페이지 대기결제 조회 중 네트워크 오류: " + e.getMessage());
            return;
        }

        JsonArray charges;
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("charges") || !root.get("charges").isJsonArray()) {
                return;
            }
            charges = root.getAsJsonArray("charges");
        } catch (Exception e) {
            logger.warn("홈페이지 대기결제 응답 파싱 실패: " + e.getMessage());
            return;
        }

        // 오래된 순(응답 순서)대로 처리
        for (int i = 0; i < charges.size(); i++) {
            try {
                processOne(charges.get(i).getAsJsonObject());
            } catch (Exception e) {
                // 한 건 실패가 나머지를 막지 않도록 개별 격리
                logger.warn("결제 건 처리 중 오류(다음 폴링에 재시도): " + e.getMessage());
            }
        }
    }

    private void processOne(JsonObject charge) {
        String chargeId = charge.has("id") ? charge.get("id").getAsString() : null;
        if (chargeId == null || chargeId.isBlank()) {
            return;
        }
        UUID uuid;
        long amount;
        try {
            uuid = parseUuid(charge.get("uuid").getAsString());
            amount = charge.get("amount").getAsLong();
        } catch (Exception e) {
            logger.warn("결제 건 " + chargeId + " 필드 오류(uuid/amount). 건너뜁니다.");
            return;
        }

        ChargeResult result;
        try {
            result = dao.processCharge(chargeId, uuid, amount);
        } catch (Exception e) {
            // DB 오류 → 지급 실패로 간주. processed 기록/보고 하지 않고 다음 폴링에 재시도.
            logger.warn("결제 건 " + chargeId + " 지급 트랜잭션 실패(재시도 예정): " + e.getMessage());
            return;
        }

        switch (result) {
            case PAID -> logger.info("홈페이지 결제 지급 완료: charge=" + chargeId + " amount=" + amount);
            case ALREADY_PROCESSED -> { /* 이미 지급됨 — 조용히 보고만 재시도 */ }
            case FAILED -> {
                return; // 보고하지 않음
            }
        }
        // 지급/스킵 여부와 무관하게 항상 보고(멱등). 실패해도 다음 폴링에 재보고.
        reportComplete(chargeId);
    }

    private void reportComplete(String chargeId) {
        try {
            String json = "{\"id\":\"" + escapeJson(chargeId) + "\"}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(completeUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("x-plugin-key", pluginKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code == 401) {
                logger.error("홈페이지 브릿지 인증 실패(401, complete): plugin-key 를 확인하세요.");
            } else if (code < 200 || code >= 300) {
                logger.warn("결제 완료 보고 실패(HTTP " + code + "): charge=" + chargeId + ". 다음 폴링에 재보고합니다.");
            }
        } catch (Exception e) {
            logger.warn("결제 완료 보고 중 네트워크 오류: charge=" + chargeId + " (" + e.getMessage() + ")");
        }
    }

    private static UUID parseUuid(String raw) {
        String s = raw.trim();
        if (s.length() == 32 && s.indexOf('-') < 0) {
            s = s.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                    "$1-$2-$3-$4-$5");
        }
        return UUID.fromString(s);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
