package kr.scfarm.credit.listener;

import kr.scfarm.credit.impl.CreditService;
import kr.scfarm.credit.resolver.NameResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 접속/퇴장 캐시 수명주기 리스너 (PlayerPoints DataManager 의 PreLogin/Join 핸들러 패턴).
 *
 * <ul>
 *   <li><b>접속:</b> 온라인 셋 등록 → placeholder 캐시 예열(비동기) + DB 닉네임 캐시 갱신(비동기).
 *       닉네임 갱신은 PlayerPoints 가 조인 때마다 username_cache 를 UPSERT 하는 것과 동일 —
 *       닉변이 즉시 반영되고, 멀티 백엔드 네트워크의 다른 서버에서도 이 유저를 해석할 수 있게 된다.</li>
 *   <li><b>퇴장:</b> 온라인 셋 제거 + placeholder 캐시 제거 → 캐시 무한 증가 방지
 *       (PlayerPoints 의 bungee 업데이트 큐 무한 누적 버그 계열 예방).</li>
 * </ul>
 *
 * <p>온라인 UUID 셋은 메인 스레드(이벤트)에서만 쓰고 비동기 태스크에서는 읽기만 한다. Bukkit 의
 * {@code getOnlinePlayers()} 를 비동기에서 부르지 않기 위한 자체 스레드세이프 사본이다.
 */
public final class PlayerCacheListener implements Listener {

    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final CreditService credit;
    private final NameResolver names;

    public PlayerCacheListener(CreditService credit, NameResolver names) {
        this.credit = credit;
        this.names = names;
    }

    /** 비동기 리프레시 태스크가 읽는 온라인 UUID 스냅샷 뷰. */
    public Set<UUID> onlineUuids() {
        return online;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        online.add(uuid);
        // 캐시 예열(비동기 DB) — 조인 직후 placeholder 첫 조회가 0 으로 보일 수 있는 시간은 수 ms 뿐.
        credit.preload(uuid);
        // 닉네임 캐시 갱신(메모리 + DB 비동기 역기입)
        names.cacheName(uuid, player.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        online.remove(uuid);
        credit.invalidateLocal(uuid);
    }
}
