package kr.scfarm.credit.impl;

import kr.scfarm.credit.api.CreditAPI;
import kr.scfarm.credit.db.CreditDao;
import kr.scfarm.credit.redis.RedisManager;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

/**
 * {@link CreditAPI} 구현. DAO 의 동기 트랜잭션을 전용 실행자(비동기 스레드 풀) 위에서 실행하여
 * {@link CompletableFuture} 로 노출한다. Bukkit API 는 여기서 절대 호출하지 않는다.
 *
 * <p>홈페이지 브릿지의 지급도 결국 DAO 의 동일 경로(잔액 UPSERT + 원장)를 타므로 지급 로직은 한 곳에만 존재한다.
 *
 * <p><b>캐시 정책(PlayerPoints pointsCache 수명주기 반영):</b> 잔액의 진실원은 DB 이고
 * 명령어(/크레딧)는 항상 DB 를 조회한다(무손실/정확 우선). 아래 {@code cache} 는 동기적으로 값을
 * 내놓아야 하는 PlaceholderAPI 전용 최적화이며,
 * <ul>
 *   <li>{@code cacheEligible}(=온라인 여부)인 UUID 만 캐시에 담아 무한 증가를 막고(퇴장 시 리스너가 제거 —
 *       PlayerPoints 의 bungee 업데이트 큐 무한 누적 버그 계열 예방),</li>
 *   <li>주기 배치 리프레시({@link #refreshBalances})로 교차서버 스테일을 해소한다
 *       (PlayerPoints 의 {@code refreshAfterWrite(cache-duration)} 에 해당).</li>
 * </ul>
 */
public final class CreditService implements CreditAPI {

    private final CreditDao dao;
    private final ExecutorService executor;
    private final ComponentLogger logger;
    private final RedisManager redis; // nullable
    private final Predicate<UUID> cacheEligible;
    private final ConcurrentHashMap<UUID, Long> cache = new ConcurrentHashMap<>();
    /** placeholder 적재 중복 방지(진행 중인 UUID). */
    private final ConcurrentHashMap<UUID, Boolean> loading = new ConcurrentHashMap<>();

    public CreditService(CreditDao dao, ExecutorService executor, ComponentLogger logger,
                         RedisManager redis, Predicate<UUID> cacheEligible) {
        this.dao = dao;
        this.executor = executor;
        this.logger = logger;
        this.redis = redis;
        this.cacheEligible = cacheEligible;
    }

    @Override
    public CompletableFuture<Long> getBalance(UUID uuid) {
        return async("getBalance", () -> {
            long bal = dao.getBalance(uuid);
            cachePut(uuid, bal);
            return bal;
        });
    }

    /** 온라인 유저만 캐시에 담고, 담은 직후 퇴장했으면 즉시 제거한다(캐시 영구 누수 방지). */
    private void cachePut(UUID uuid, long balance) {
        if (!cacheEligible.test(uuid)) {
            return;
        }
        cache.put(uuid, balance);
        if (!cacheEligible.test(uuid)) {
            cache.remove(uuid);
        }
    }

    @Override
    public CompletableFuture<Boolean> give(UUID uuid, long amount, String reason) {
        return async("give", () -> {
            boolean ok = dao.give(uuid, amount, reason, null);
            if (ok) {
                onChanged(uuid);
            }
            return ok;
        });
    }

    @Override
    public CompletableFuture<Boolean> take(UUID uuid, long amount, String reason) {
        return async("take", () -> {
            boolean ok = dao.take(uuid, amount, reason, null);
            if (ok) {
                onChanged(uuid);
            }
            return ok;
        });
    }

    @Override
    public CompletableFuture<Boolean> set(UUID uuid, long amount, String reason) {
        return async("set", () -> {
            boolean ok = dao.set(uuid, amount, reason);
            if (ok) {
                onChanged(uuid);
            }
            return ok;
        });
    }

    @Override
    public CompletableFuture<Boolean> pay(UUID from, UUID to, long amount, String reason) {
        return async("pay", () -> {
            boolean ok = dao.pay(from, to, amount, reason);
            if (ok) {
                onChanged(from);
                onChanged(to);
            }
            return ok;
        });
    }

    // ── 캐시 수명주기 ───────────────────────────────────────────────────────

    /**
     * 캐시에 있으면 즉시 반환(PlaceholderAPI 동기 조회용), 없으면 비동기 적재를 예약하고 null 반환.
     *
     * <p>플레이스홀더는 스코어보드/홀로그램 때문에 <b>매 틱, 시청자 수만큼</b> 호출될 수 있다.
     * 적재 중복 요청을 막지 않으면 캐시가 비어 있는 동안 매 틱 DB 태스크가 쌓여(무제한 큐)
     * DB 를 더 느리게 만들고 결국 힙이 터진다 → UUID 당 진행 중 적재를 1건으로 제한한다.
     */
    public Long peekCache(UUID uuid) {
        Long v = cache.get(uuid);
        if (v == null && cacheEligible.test(uuid) && loading.putIfAbsent(uuid, Boolean.TRUE) == null) {
            getBalance(uuid).whenComplete((bal, ex) -> loading.remove(uuid));
        }
        return v;
    }

    /** 접속 시 캐시 예열(리스너에서 호출). */
    public void preload(UUID uuid) {
        getBalance(uuid);
    }

    /** 이 서버 로컬 캐시만 무효화(퇴장 리스너/Redis 구독 콜백에서 호출). */
    public void invalidateLocal(UUID uuid) {
        cache.remove(uuid);
    }

    /**
     * 외부 경로(홈페이지 브릿지 등)로 잔액이 바뀌었을 때 호출 — 캐시 무효화 + 재적재 + Redis 브로드캐스트.
     * 브릿지 지급 직후 placeholder 가 옛 값을 보여주는 지연(PlayerPoints 의 "placeholder delay" 버그 계열)을 막는다.
     */
    public void notifyExternalChange(UUID uuid) {
        onChanged(uuid);
    }

    /**
     * 온라인 유저 잔액 일괄 리프레시(주기 태스크에서 호출, 쿼리 1번).
     * 다른 서버에서 바뀐 잔액도 여기서 따라잡는다 — Redis 를 꺼도 placeholder 스테일이 이 주기로 수렴.
     */
    public CompletableFuture<Void> refreshBalances(Collection<UUID> uuids) {
        if (uuids.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return async("refreshBalances", () -> {
            Map<UUID, Long> balances = dao.getBalances(uuids);
            for (Map.Entry<UUID, Long> e : balances.entrySet()) {
                cachePut(e.getKey(), e.getValue());
            }
            // 퇴장했는데 경합으로 남아 있는 항목을 주기적으로 정리(캐시 무한 증가 방지)
            cache.keySet().removeIf(u -> !cacheEligible.test(u));
            return null;
        });
    }

    /**
     * 잔액 변경 후 처리: 로컬 캐시 무효화 → 교차서버 브로드캐스트 → (온라인이면) 재적재.
     *
     * <p><b>이미 커밋된 뒤에 실행되므로 절대 예외를 밖으로 던지지 않는다.</b> 여기서 던지면
     * (예: 종료 중 executor 거부) 커밋에 성공한 give/take 가 호출자에게 실패로 보고되고,
     * 관리자/상점이 재시도해 <b>이중 지급·이중 결제</b>가 발생한다.
     */
    private void onChanged(UUID uuid) {
        try {
            cache.remove(uuid);
            if (redis != null) {
                redis.publishInvalidate(uuid);
            }
            if (!cacheEligible.test(uuid)) {
                return; // 오프라인 유저는 캐시에 다시 담지 않는다(무한 증가 방지)
            }
            executor.execute(() -> {
                try {
                    long bal = dao.getBalance(uuid);
                    cache.put(uuid, bal);
                    // put 직후 퇴장했다면 남은 항목을 정리(캐시 영구 누수 방지)
                    if (!cacheEligible.test(uuid)) {
                        cache.remove(uuid);
                    }
                } catch (Throwable ignored) {
                    // 캐시 재적재 실패는 무시(다음 조회에서 DB 로 폴백)
                }
            });
        } catch (Throwable ignored) {
            // 커밋된 결과를 뒤집지 않는다 — 캐시/브로드캐스트 실패는 무시
        }
    }

    private interface SqlCallable<T> {
        T call() throws Exception;
    }

    private <T> CompletableFuture<T> async(String op, SqlCallable<T> body) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(body.call());
                } catch (Throwable t) {
                    logger.warn("크레딧 " + op + " 처리 중 오류", t);
                    future.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            // 종료 중 RejectedExecutionException 등 — 호출 스레드로 던지지 않고 future 로 전달한다
            // (호출자가 예외를 동기적으로 맞고 이중 처리하는 것을 방지)
            future.completeExceptionally(t);
        }
        return future;
    }
}
