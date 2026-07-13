package kr.scfarm.credit.impl;

import kr.scfarm.credit.api.CreditAPI;
import kr.scfarm.credit.db.CreditDao;
import kr.scfarm.credit.redis.RedisManager;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * {@link CreditAPI} 구현. DAO 의 동기 트랜잭션을 전용 실행자(비동기 스레드 풀) 위에서 실행하여
 * {@link CompletableFuture} 로 노출한다. Bukkit API 는 여기서 절대 호출하지 않는다.
 *
 * <p>홈페이지 브릿지의 지급도 결국 DAO 의 동일 경로(잔액 UPSERT + 원장)를 타므로 지급 로직은 한 곳에만 존재한다.
 *
 * <p><b>캐시 정책:</b> 잔액의 진실원은 DB 이고 명령어(/크레딧)는 항상 DB 를 조회한다(무손실/정확 우선).
 * 아래 {@code cache} 는 동기적으로 값을 내놓아야 하는 PlaceholderAPI 전용 최적화이며,
 * 변경 시 무효화 + (선택)Redis Pub/Sub 브로드캐스트로 교차서버 스테일을 방지한다.
 */
public final class CreditService implements CreditAPI {

    private final CreditDao dao;
    private final ExecutorService executor;
    private final ComponentLogger logger;
    private final RedisManager redis; // nullable
    private final ConcurrentHashMap<UUID, Long> cache = new ConcurrentHashMap<>();

    public CreditService(CreditDao dao, ExecutorService executor, ComponentLogger logger, RedisManager redis) {
        this.dao = dao;
        this.executor = executor;
        this.logger = logger;
        this.redis = redis;
    }

    @Override
    public CompletableFuture<Long> getBalance(UUID uuid) {
        return async("getBalance", () -> {
            long bal = dao.getBalance(uuid);
            cache.put(uuid, bal);
            return bal;
        });
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

    // ── PlaceholderAPI 지원(동기 조회용 캐시) ───────────────────────────────

    /** 캐시에 있으면 즉시 반환, 없으면 비동기 적재를 예약하고 null 반환. */
    public Long peekCache(UUID uuid) {
        Long v = cache.get(uuid);
        if (v == null) {
            getBalance(uuid); // 백그라운드 적재(결과는 캐시에 채워짐)
        }
        return v;
    }

    /** 이 서버 로컬 캐시만 무효화(Redis 구독 콜백에서 호출). */
    public void invalidateLocal(UUID uuid) {
        cache.remove(uuid);
    }

    /** 잔액 변경 후 처리: 로컬 캐시 무효화 → 재적재 → 교차서버 브로드캐스트. */
    private void onChanged(UUID uuid) {
        cache.remove(uuid);
        if (redis != null) {
            redis.publishInvalidate(uuid);
        }
        // 최신값을 캐시에 다시 채워 둔다(다음 placeholder 조회 대비). 실패해도 무시.
        executor.execute(() -> {
            try {
                cache.put(uuid, dao.getBalance(uuid));
            } catch (Exception ignored) {
                // 캐시 재적재 실패는 무시(다음 조회에서 DB 로 폴백)
            }
        });
    }

    private interface SqlCallable<T> {
        T call() throws Exception;
    }

    private <T> CompletableFuture<T> async(String op, SqlCallable<T> body) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(body.call());
            } catch (Throwable t) {
                logger.warn("크레딧 " + op + " 처리 중 오류", t);
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
