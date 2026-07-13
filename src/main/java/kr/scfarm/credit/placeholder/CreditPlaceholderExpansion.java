package kr.scfarm.credit.placeholder;

import kr.scfarm.credit.impl.CreditService;
import kr.scfarm.credit.util.Messages;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI 확장(선택). {@code %credit_balance%}(원값) / {@code %credit_balance_formatted%}(콤마+접미사).
 *
 * <p>PlaceholderAPI 는 동기 API 라 DB 를 블로킹할 수 없다. 따라서 {@link CreditService} 의 캐시를 읽고,
 * 값이 없으면 백그라운드 적재를 예약한 뒤 "0" 을 반환한다(다음 조회부터 최신값). 정확한 잔액은 항상 /크레딧(DB).
 */
public final class CreditPlaceholderExpansion extends PlaceholderExpansion {

    private final CreditService credit;
    private final Messages msg;

    public CreditPlaceholderExpansion(CreditService credit, Messages msg) {
        this.credit = credit;
        this.msg = msg;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "credit";
    }

    @Override
    public @NotNull String getAuthor() {
        return "마인팜";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true; // /papi reload 후에도 유지
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        Long cached = credit.peekCache(player.getUniqueId());
        long balance = cached == null ? 0L : cached;
        return switch (params.toLowerCase()) {
            case "balance" -> Long.toString(balance);
            case "balance_formatted" -> msg.formatAmount(balance) + msg.suffix();
            default -> null;
        };
    }
}
