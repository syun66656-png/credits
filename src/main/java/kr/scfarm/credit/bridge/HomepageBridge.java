package kr.scfarm.credit.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.scfarm.credit.db.ChargeOutcome;
import kr.scfarm.credit.db.CreditDao;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

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
    /** 응답 본문 상한(바이트). 장애 페이지/악의적 엔드포인트로 인한 힙 고갈 방지(최대 200건이면 충분). */
    private static final int MAX_RESPONSE_BYTES = 4_000_000;

    /**
     * 크레딧 환산 비율 — 1,000원 = 1크레딧.
     * 홈페이지가 {@code credits} 를 내려주므로 보통 쓰이지 않고, 구버전 응답 폴백에만 사용한다.
     */
    private static final long WON_PER_CREDIT = 1000L;

    private final String pendingUrl;
    private final String completeUrl;
    private final String pluginKey;
    private final CreditDao dao;
    private final ComponentLogger logger;
    private final HttpClient http;
    /**
     * 지급 성공 시 (uuid, 지급 크레딧) 으로 호출 — 캐시 무효화/Redis 브로드캐스트/온라인 알림용.
     * 결제 금액(원)이 아니라 실제 지급된 크레딧 수량이 전달된다.
     * 비동기 스레드에서 호출되므로 구현측에서 Bukkit 이 필요하면 메인 스레드로 디스패치해야 한다.
     */
    private final BiConsumer<UUID, Long> onPaid;
    /** 지급 로그 yml 파일(플러그인 폴더). null 이면 파일 로그 미기록. */
    private final ChargeLogFile chargeLog;
    /**
     * 이미 ERROR 를 남긴 거부 건. 거부된 결제는 완료 보고를 하지 않으므로 매 폴링마다 다시 내려온다 —
     * 로그가 무한히 쌓이지 않도록 건당 한 번만 남긴다(수동 정산 후 사라짐).
     */
    private final Set<String> reportedRejects = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public HomepageBridge(String baseUrl, String pluginKey, CreditDao dao,
                          ComponentLogger logger, BiConsumer<UUID, Long> onPaid, ChargeLogFile chargeLog) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.pendingUrl = base + "/api/plugin/charges/pending";
        this.completeUrl = base + "/api/plugin/charges/complete";
        this.pluginKey = pluginKey;
        this.dao = dao;
        this.logger = logger;
        this.onPaid = onPaid;
        this.chargeLog = chargeLog;
        // 리다이렉트를 절대 따라가지 않는다(보안상 필수):
        //  1) JDK HttpClient 는 교차 호스트 리다이렉트에서도 커스텀 헤더(x-plugin-key)를 그대로 재전송한다
        //     → 도메인 탈취/오픈 리다이렉트 하나로 비밀키가 외부에 유출되고, 공격자가 pending 응답을
        //       조작해 임의 크레딧을 발행할 수 있다.
        //  2) 301/302 는 POST 를 본문 없는 GET 으로 바꿔버린다 → 완료 보고가 조용히 실패하고
        //     같은 건이 영원히 재처리 대기로 남는다(www/https 정규화 같은 흔한 설정 변경으로 발생).
        // 3xx 는 설정 오류로 간주해 크게 로그를 남긴다.
        this.http = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 3xx 응답을 설정 오류로 처리(리다이렉트를 따라가면 키 유출/보고 유실 위험). */
    private boolean isRedirect(int code, String what) {
        if (code >= 300 && code < 400) {
            logger.error("홈페이지 브릿지 " + what + ": 리다이렉트(HTTP " + code + ") 응답을 받았습니다. "
                    + "base-url 을 최종 주소로 정확히 설정하세요(리다이렉트는 보안상 따라가지 않습니다).");
            return true;
        }
        return false;
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
            // 스트림으로 받아 상한까지만 읽는다. ofString 은 본문을 통째로 먼저 메모리에 올리므로
            // 상한 검사가 사후약방문이 된다(거대 응답 = 서버 힙 고갈).
            HttpResponse<java.io.InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream in = res.body()) {
                if (res.statusCode() == 401) {
                    logger.error("홈페이지 브릿지 인증 실패(401): plugin-key 를 확인하세요.");
                    return;
                }
                if (isRedirect(res.statusCode(), "대기결제 조회")) {
                    return;
                }
                if (res.statusCode() != 200) {
                    logger.warn("홈페이지 대기결제 조회 실패(HTTP " + res.statusCode() + "). 다음 폴링에 재시도합니다.");
                    return;
                }
                byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    logger.error("홈페이지 대기결제 응답이 비정상적으로 큽니다(" + MAX_RESPONSE_BYTES
                            + "바이트 초과). 처리를 건너뜁니다.");
                    return;
                }
                body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            // 네트워크 오류/타임아웃 → 다음 폴링에 재시도(크래시 금지).
            // 주의: 예외 메세지에 요청 헤더가 섞여 나올 수 있으므로 예외 타입만 남기고 메세지는 찍지 않는다
            // (plugin-key 가 로그 파일에 노출되는 것을 원천 차단).
            logger.warn("홈페이지 대기결제 조회 중 네트워크 오류: " + e.getClass().getSimpleName());
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
        long amount;  // 결제 금액(원) — 감사 로그용. 지급 수량이 아니다.
        long credits; // 실제 지급할 크레딧 수량
        try {
            uuid = parseUuid(charge.get("uuid").getAsString());
            // amount/credits 는 반드시 정수 JSON 숫자여야 한다. 문자열/실수/거대수를 그냥 getAsLong 하면
            // 조용히 절삭되거나 2^64 로 감싸져 엉뚱한 수량이 지급될 수 있다.
            amount = strictAmount(charge.get("amount"));
            credits = readCredits(charge, amount);
        } catch (Exception e) {
            // 완료 보고를 하지 않으므로 같은 건이 매 폴링마다 다시 내려온다 → 건당 1회만 남긴다.
            if (reportedRejects.add(chargeId)) {
                logger.error("[크레딧 자동충전 거부] charge=" + chargeId
                        + " uuid/amount/credits 필드가 부적합합니다(" + e.getMessage() + "). "
                        + "지급하지 않고 완료 보고도 하지 않습니다 — 수동 확인이 필요합니다.");
            }
            return;
        }
        // 지급 수량이 0 이하면 아래 processCharge 가 REJECTED 를 돌려준다 → 지급도, 완료 보고도 하지 않고
        // 그 분기에서 건당 한 번만 ERROR 를 남긴다(미지급 건을 '처리완료'로 보고하면 유저가 결제하고
        // 아무것도 못 받는 조용한 손실이 된다). 이미 알린 건이면 여기서 바로 끊어 로그 반복을 막는다.
        if (credits <= 0 && reportedRejects.contains(chargeId)) {
            return;
        }
        // 닉네임은 참고용(감사 기록·로그). 지급 키는 항상 uuid — 없거나 이상해도 지급엔 영향 없음.
        String nickname = null;
        try {
            if (charge.has("nickname") && !charge.get("nickname").isJsonNull()) {
                nickname = charge.get("nickname").getAsString();
            }
        } catch (Exception ignored) {
            // 닉네임 파싱 실패는 무시
        }
        if (nickname != null && nickname.length() > 30) {
            nickname = nickname.substring(0, 30); // DB 컬럼과 로그 파일 기록을 동일하게 맞춘다
        }

        ChargeOutcome outcome;
        try {
            outcome = dao.processCharge(chargeId, uuid, nickname, credits);
        } catch (Exception e) {
            // DB 오류 → 지급 실패로 간주. processed 기록/보고 하지 않고 다음 폴링에 재시도.
            logger.warn("결제 건 " + chargeId + " 지급 트랜잭션 실패(재시도 예정): " + e.getMessage());
            return;
        }

        switch (outcome.status()) {
            case PAID -> {
                // 분쟁 방지 감사 로그 — DB(credit_processed_charge/credit_ledger)와 동일 내용을
                // 서버 로그 파일에도 남긴다(시간은 로그 타임스탬프 + DB processed_at 양쪽 보존).
                logger.info("[크레딧 자동충전] charge=" + chargeId
                        + " 닉네임=" + (nickname == null ? "?" : nickname)
                        + " uuid=" + uuid
                        + " 결제액=" + amount + "원"
                        + " 지급=" + credits + "크레딧"
                        + " 지급전=" + outcome.balanceBefore()
                        + " 지급후=" + outcome.balanceAfter());
                // 플러그인 폴더 안 yml 파일에도 기록(사람이 바로 열어볼 수 있는 append-only 로그)
                if (chargeLog != null) {
                    chargeLog.append(chargeId, uuid, nickname, amount, credits,
                            outcome.balanceBefore(), outcome.balanceAfter());
                }
                // 지급 직후 캐시 통지 + (이 백엔드에 접속 중이면) 인게임 알림
                try {
                    onPaid.accept(uuid, credits);
                } catch (Exception e) {
                    logger.warn("지급 후 통지 실패(무시): " + e.getMessage());
                }
            }
            case ALREADY_PROCESSED -> { /* 이미 지급됨 — 조용히 보고만 재시도 */ }
            case REJECTED -> {
                // 지급하지 않았으므로 절대 완료 보고하지 않는다(보고하면 홈페이지가 지급됨으로 확정 →
                // 결제한 유저가 아무것도 못 받고 추적도 불가능). 홈페이지에 미지급으로 남겨 수동 정산.
                if (reportedRejects.add(chargeId)) {
                    logger.error("[크레딧 자동충전 거부] charge=" + chargeId
                            + " uuid=" + uuid + " 결제액=" + amount + "원 지급크레딧=" + credits
                            + " — 지급 수량이 범위(1~" + CreditDao.MAX_AMOUNT + ")를 벗어났거나"
                            + " charge_id 길이가 부적합합니다. 지급/완료보고 모두 하지 않았습니다."
                            + " 수동 확인이 필요합니다.");
                }
                return;
            }
            case BALANCE_MISMATCH -> {
                // 지급 전/후 잔액 대조가 어긋나 전체 롤백된 상태 — 잔액은 그대로다.
                // 완료 보고를 하지 않아 홈페이지에는 미지급으로 남는다(다음 폴링에 재시도).
                logger.error("[크레딧 자동충전 검증실패] charge=" + chargeId
                        + " uuid=" + uuid + " 지급크레딧=" + credits
                        + " 지급전=" + outcome.balanceBefore() + " 지급후=" + outcome.balanceAfter()
                        + " — 잔액 증가분이 지급 수량과 일치하지 않아 지급을 취소했습니다."
                        + " 완료 보고도 하지 않았습니다. DB 트리거/외부 수정 여부를 확인하세요.");
                return;
            }
            case FAILED -> {
                return; // 보고하지 않음
            }
            default -> {
                return; // 알 수 없는 상태는 절대 완료 보고하지 않는다(미지급 건을 완료 처리하는 사고 방지)
            }
        }
        // 지급됐거나 이미 지급된 건만 보고(멱등). 실패해도 다음 폴링에 재보고.
        reportComplete(chargeId);
    }

    private void reportComplete(String chargeId) {
        try {
            // 수기 문자열 조립 대신 Gson 으로 직렬화(제어문자/따옴표가 섞여도 항상 유효한 JSON)
            JsonObject payload = new JsonObject();
            payload.addProperty("id", chargeId);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(completeUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("x-plugin-key", pluginKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code == 401) {
                logger.error("홈페이지 브릿지 인증 실패(401, complete): plugin-key 를 확인하세요.");
            } else if (isRedirect(code, "완료 보고")) {
                // isRedirect 가 이미 ERROR 로 남김 — 리다이렉트를 따라가면 POST 가 GET 으로 바뀌어
                // 보고가 조용히 유실된다(같은 건이 영원히 대기로 남음).
                logger.error("결제 완료 보고가 리다이렉트로 실패했습니다: charge=" + chargeId);
            } else if (code < 200 || code >= 300) {
                logger.warn("결제 완료 보고 실패(HTTP " + code + "): charge=" + chargeId + ". 다음 폴링에 재보고합니다.");
            }
        } catch (Exception e) {
            // 예외 메세지에 요청 헤더가 섞일 수 있어 타입만 남긴다(plugin-key 노출 방지)
            logger.warn("결제 완료 보고 중 네트워크 오류: charge=" + chargeId
                    + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * amount 를 엄격히 해석한다. 정수 JSON 숫자만 허용 — 문자열/실수/거대수는 거부한다.
     * (Gson 의 getAsLong 은 "5000.9" 를 5000 으로 절삭하고 1e30 을 2^64 모듈로로 감싸버린다)
     */
    private static long strictAmount(JsonElement el) {
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("amount 가 숫자가 아님");
        }
        java.math.BigDecimal bd = new java.math.BigDecimal(el.getAsJsonPrimitive().getAsString());
        try {
            return bd.longValueExact(); // 소수/범위 초과면 ArithmeticException
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount 가 정수 범위를 벗어남: " + bd.toPlainString());
        }
    }

    /**
     * 지급할 크레딧 수량을 읽는다.
     *
     * <p>홈페이지는 {@code credits} 필드로 지급 수량을 내려준다. 구버전 응답에 이 필드가 없을 때만
     * 결제 금액에서 환산한다(1,000원 = 1크레딧). {@code amount} 를 그대로 지급하면 1,000배
     * 과지급이 되므로 어떤 경로에서도 그렇게 하지 않는다.
     */
    private static long readCredits(JsonObject charge, long amount) {
        if (charge.has("credits") && !charge.get("credits").isJsonNull()) {
            // amount 와 동일하게 엄격 파싱 — 실수/문자열/거대수를 조용히 절삭해 지급하면 안 된다.
            return strictAmount(charge.get("credits"));
        }
        return amount / WON_PER_CREDIT;
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
}
