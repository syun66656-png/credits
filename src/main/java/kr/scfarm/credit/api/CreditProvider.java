package kr.scfarm.credit.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * {@link CreditAPI} 정적 접근자. Vault 의 {@code Economy} 접근 패턴과 동일하게,
 * Bukkit {@code ServicesManager} 에 등록된 구현을 조회한다.
 *
 * <pre>{@code
 * CreditAPI credit = CreditProvider.get();
 * if (credit != null) credit.take(uuid, 1000, "SHOP_TAKE");
 * }</pre>
 */
public final class CreditProvider {

    private CreditProvider() {
    }

    /**
     * 등록된 크레딧 API 구현을 반환한다. 플러그인이 비활성(DB 연결 실패 등)이면 {@code null}.
     *
     * @return CreditAPI 구현 또는 null
     */
    public static CreditAPI get() {
        RegisteredServiceProvider<CreditAPI> rsp =
                Bukkit.getServicesManager().getRegistration(CreditAPI.class);
        return rsp != null ? rsp.getProvider() : null;
    }
}
