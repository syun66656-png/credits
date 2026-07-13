package kr.scfarm.credit.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 크레딧 공개 API.
 *
 * <p>캐시 상점 등 외부 플러그인이 크레딧을 조회/지급/차감할 때 사용하는 안정적인 진입점이다.
 * 모든 연산은 <b>비동기</b>({@link CompletableFuture})이며 <b>원자적</b>이다. 반환된 future 는
 * 크레딧 연산 전용 스레드에서 완료되므로, Bukkit API 를 호출해야 한다면 메인 스레드로 넘겨서 사용한다.
 *
 * <p>모든 연산은 UUID 기반이라 <b>오프라인/미접속 유저</b>에게도 안전하게 동작한다.
 * (PlayerPoints 의 {@code PlayerPointsAPI} 설계(look/give/take/set/pay)를 참고하되,
 * 우리는 금액을 {@code long} 으로 확장하고 반환을 {@code CompletableFuture} 로 비동기화했다.)
 *
 * <p>획득 방법:
 * <pre>{@code
 * CreditAPI credit = CreditProvider.get();      // 정적 접근자
 * // 또는 Bukkit ServicesManager 로부터:
 * CreditAPI credit = Bukkit.getServicesManager().load(CreditAPI.class);
 * }</pre>
 */
public interface CreditAPI {

    /**
     * 잔액 조회. 계정이 없으면 0.
     *
     * @param uuid 대상 UUID
     * @return 현재 잔액
     */
    CompletableFuture<Long> getBalance(UUID uuid);

    /**
     * 지급(원자적 UPSERT). 같은 트랜잭션에서 원장(ledger)에 기록된다.
     *
     * @param uuid   대상 UUID
     * @param amount 지급 금액(0 이하는 실패)
     * @param reason 원장 사유(예: ADMIN_GIVE, HOMEPAGE_CHARGE)
     * @return 성공 여부
     */
    CompletableFuture<Boolean> give(UUID uuid, long amount, String reason);

    /**
     * 차감(원자적 조건부 UPDATE). <b>잔액이 부족하면 아무것도 바꾸지 않고 {@code false}</b> 를 반환한다.
     * 잔액은 절대 음수가 되지 않는다.
     *
     * @param uuid   대상 UUID
     * @param amount 차감 금액(0 이하는 실패)
     * @param reason 원장 사유(예: SHOP_TAKE, ADMIN_TAKE)
     * @return 성공 여부(잔액 부족 시 false, 잔액 불변)
     */
    CompletableFuture<Boolean> take(UUID uuid, long amount, String reason);

    /**
     * 잔액을 지정 값으로 설정. 증감분(delta)은 원장에 기록된다.
     *
     * @param uuid   대상 UUID
     * @param amount 설정할 잔액(0 이상)
     * @param reason 원장 사유(예: ADMIN_SET)
     * @return 성공 여부
     */
    CompletableFuture<Boolean> set(UUID uuid, long amount, String reason);

    /**
     * 계정 간 이체를 <b>단일 트랜잭션</b>으로 수행한다. 출금 계정 잔액이 부족하면 전체 롤백 후 {@code false}.
     *
     * @param from   출금 UUID
     * @param to     입금 UUID
     * @param amount 이체 금액(0 이하는 실패)
     * @param reason 원장 사유(예: SHOP_TRADE)
     * @return 성공 여부
     */
    CompletableFuture<Boolean> pay(UUID from, UUID to, long amount, String reason);
}
