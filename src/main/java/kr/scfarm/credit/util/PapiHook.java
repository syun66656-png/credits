package kr.scfarm.credit.util;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI 호출을 이 클래스에만 격리한다. {@link Messages} 는 PAPI 가 설치돼 있을 때만
 * 이 클래스를 건드리므로, PAPI 미설치 환경에서 {@code me.clip} 클래스 참조로 인한
 * {@code NoClassDefFoundError} 가 발생하지 않는다(클래스 로딩/검증이 첫 호출까지 지연됨).
 *
 * <p>주의: 일부 PAPI 확장은 메인 스레드를 가정하므로, 이 메서드는 <b>메인 스레드에서만</b> 호출한다.
 */
final class PapiHook {

    private PapiHook() {
    }

    /** {@code %...%} 플레이스홀더를 해석한 문자열을 돌려준다(MiniMessage 파싱 전에 적용). */
    static String apply(OfflinePlayer player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}
