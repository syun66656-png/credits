package kr.scfarm.credit;

import kr.scfarm.credit.api.CreditAPI;
import kr.scfarm.credit.bridge.HomepageBridge;
import kr.scfarm.credit.command.CreditCommand;
import kr.scfarm.credit.db.CreditDao;
import kr.scfarm.credit.db.DatabaseManager;
import kr.scfarm.credit.impl.CreditService;
import kr.scfarm.credit.listener.PlayerCacheListener;
import kr.scfarm.credit.placeholder.CreditPlaceholderExpansion;
import kr.scfarm.credit.redis.RedisManager;
import kr.scfarm.credit.resolver.NameResolver;
import kr.scfarm.credit.util.ConsoleLog;
import kr.scfarm.credit.util.Messages;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 크레딧 플러그인 진입점.
 *
 * <p>onEnable 순서: DB 연결 → 통일 콘솔 메세지 → (실패 시 비활성화) → 스키마 → 실행자/서비스/명령어/브릿지.
 * MariaDB 연결에 실패하면 크레딧 연산을 수행하지 않고 스스로 비활성화한다(깨진 DB 쓰기로 인한 유실 방지 — 명세 0번).
 */
public final class CreditPlugin extends JavaPlugin {

    private ConsoleLog console;
    private DatabaseManager database;
    private ExecutorService executor;
    private RedisManager redis;
    private CreditService creditService;

    @Override
    public void onEnable() {
        ComponentLogger clog = getComponentLogger();
        this.console = new ConsoleLog(clog);

        saveDefaultConfig();
        saveDefaultResource("messages.yml");
        FileConfiguration config = getConfig();

        // 1) MariaDB 연결(커넥션 풀 초기화 + 테스트 쿼리). 실패 시 비활성화.
        try {
            this.database = new DatabaseManager(config.getConfigurationSection("database"));
        } catch (Throwable t) {
            // HikariCP 초기 커넥션 실패 등
            console.logConn("MariaDB", false);
            getComponentLogger().error("MariaDB 커넥션 풀 초기화 실패: " + t.getMessage());
            disableSelf();
            return;
        }
        if (!database.testConnection()) {
            console.logConn("MariaDB", false);
            disableSelf();
            return;
        }
        console.logConn("MariaDB", true);

        // 2) 스키마 자동 생성
        try {
            database.initSchema();
        } catch (Exception e) {
            getComponentLogger().error("스키마 초기화 실패 — 비활성화합니다.", e);
            disableSelf();
            return;
        }

        // 3) 비동기 실행자(크레딧 연산 전용). 크기는 커넥션 풀에 맞춘다.
        int poolSize = Math.max(1, config.getInt("database.pool-size", 10));
        this.executor = Executors.newFixedThreadPool(poolSize, namedDaemonFactory());

        CreditDao dao = new CreditDao(database);

        // 4) (선택) Redis 교차서버 캐시 무효화
        this.redis = null;
        ConfigurationSection redisSec = config.getConfigurationSection("redis");
        if (redisSec != null && redisSec.getBoolean("enabled", false)) {
            try {
                RedisManager rm = new RedisManager(redisSec, clog, uuid -> {
                    if (creditService != null) {
                        creditService.invalidateLocal(uuid);
                    }
                });
                boolean ok = rm.connect();
                console.logConn("Redis", ok);
                this.redis = ok ? rm : null;
            } catch (Throwable t) {
                // lettuce 미탑재(NoClassDefFoundError) 등 → 캐시 없이 진행
                console.logConn("Redis", false);
                getComponentLogger().warn("Redis 를 사용할 수 없어 캐시 없이 DB 조회로 진행합니다. "
                        + "(plugin.yml 의 libraries 에 lettuce-core 추가 필요)");
                this.redis = null;
            }
        }

        // 5) 접속/퇴장 캐시 리스너 + 크레딧 서비스 + 공개 API 등록(ServicesManager)
        //    placeholder 캐시는 온라인 유저만 담는다(무한 증가 방지 — PlayerPoints 캐시 수명주기 반영).
        NameResolver names = new NameResolver(executor, dao, clog);
        final PlayerCacheListener[] listenerRef = new PlayerCacheListener[1];
        this.creditService = new CreditService(dao, executor, clog, redis,
                uuid -> listenerRef[0] != null && listenerRef[0].onlineUuids().contains(uuid));
        PlayerCacheListener cacheListener = new PlayerCacheListener(creditService, names);
        listenerRef[0] = cacheListener;
        getServer().getPluginManager().registerEvents(cacheListener, this);
        getServer().getServicesManager().register(CreditAPI.class, creditService, this, ServicePriority.Normal);

        // 6) 메세지 + 명령어
        Messages messages = loadMessages(config);
        CreditCommand command = new CreditCommand(creditService, messages, names);
        if (getCommand("크레딧") != null) {
            getCommand("크레딧").setExecutor(command);
            getCommand("크레딧").setTabCompleter(command);
        } else {
            getComponentLogger().warn("plugin.yml 에 '크레딧' 명령어가 등록되지 않았습니다.");
        }

        // 7) (선택) PlaceholderAPI
        registerPlaceholders(messages);

        // 8) 홈페이지 결제 브릿지(비동기 폴링). 지급 성공 시 캐시/Redis 통지 + 온라인 유저 인게임 알림.
        startHomepageBridge(config, dao, messages, cacheListener);

        // 9) 온라인 유저 placeholder 캐시 주기 리프레시(배치 쿼리 1회, 비동기).
        //    PlayerPoints 의 refreshAfterWrite(cache-duration) 에 해당 — Redis 없이도
        //    다른 서버에서 바뀐 잔액이 이 주기로 따라잡힌다. 0 이하로 두면 끈다.
        int refreshSeconds = config.getInt("cache.refresh-seconds", 30);
        if (refreshSeconds > 0) {
            getServer().getAsyncScheduler().runAtFixedRate(
                    this,
                    task -> creditService.refreshBalances(Set.copyOf(cacheListener.onlineUuids())),
                    refreshSeconds,
                    refreshSeconds,
                    TimeUnit.SECONDS);
        }

        getComponentLogger().info(net.kyori.adventure.text.Component.text("크레딧 활성화 완료.", ConsoleLog.OK));
    }

    @Override
    public void onDisable() {
        // 서비스 해제
        getServer().getServicesManager().unregisterAll(this);

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (redis != null) {
            redis.close();
        }
        if (database != null) {
            database.close();
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private void startHomepageBridge(FileConfiguration config, CreditDao dao,
                                     Messages messages, PlayerCacheListener cacheListener) {
        ConfigurationSection hp = config.getConfigurationSection("homepage");
        if (hp == null) {
            return;
        }
        // 벨로시티 다중 백엔드에서는 브릿지 폴링을 "한 서버에서만" 켜야 한다.
        // 그래야 (1) 홈페이지 API 를 서버 수만큼 중복 폴링하지 않고,
        //        (2) [크레딧 자동충전] 감사 로그가 항상 그 한 서버의 로그 파일에만 남아 추적이 쉽다.
        // (지급 자체는 charge_id PK 멱등이라 여러 서버가 켜져도 중복 지급은 없지만, 로그가 흩어진다.)
        if (!hp.getBoolean("bridge-enabled", true)) {
            getComponentLogger().info("홈페이지 브릿지: bridge-enabled=false → 이 서버에서는 폴링하지 않습니다(크레딧 기능은 정상).");
            return;
        }
        int interval = hp.getInt("poll-interval-seconds", 60);
        String baseUrl = hp.getString("base-url", "");
        String key = hp.getString("plugin-key", "");
        if (interval <= 0) {
            getComponentLogger().info("홈페이지 브릿지: poll-interval-seconds<=0 → 비활성.");
            return;
        }
        if (baseUrl == null || baseUrl.isBlank() || key == null || key.isBlank()
                || key.equals("여기에_PLUGIN_API_KEY")) {
            getComponentLogger().warn("홈페이지 브릿지: base-url/plugin-key 미설정 → 폴링을 시작하지 않습니다.");
            return;
        }

        HomepageBridge bridge = new HomepageBridge(baseUrl, key, dao, getComponentLogger(),
                (uuid, amount) -> {
                    creditService.notifyExternalChange(uuid);
                    // 온라인 유저 지급 알림 — 벨로시티 네트워크의 여러 백엔드 중 "이 플러그인이 설치된
                    // 이 서버"에 접속 중인 경우에만 보낸다. 오프라인/타 백엔드 접속자는 조용히 지급만.
                    // 온라인 판정은 자체 스레드세이프 셋(비동기 안전) → 실제 전송은 메인 스레드로 디스패치.
                    if (cacheListener.onlineUuids().contains(uuid)) {
                        getServer().getGlobalRegionScheduler().execute(this, () -> {
                            org.bukkit.entity.Player p = getServer().getPlayer(uuid);
                            if (p != null) { // 디스패치 사이에 퇴장했으면 조용히 스킵
                                p.sendMessage(messages.amount("charge-received", amount));
                            }
                        });
                    }
                });
        // Paper 비동기 스케줄러: 메인 스레드를 절대 막지 않는다.
        getServer().getAsyncScheduler().runAtFixedRate(
                this,
                task -> bridge.pollOnce(),
                Math.min(10, interval), // 최초 지연(초)
                interval,
                TimeUnit.SECONDS);
        getComponentLogger().info("홈페이지 브릿지: " + interval + "초 주기 폴링 시작.");
    }

    private void registerPlaceholders(Messages messages) {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            new CreditPlaceholderExpansion(creditService, messages).register();
            getComponentLogger().info("PlaceholderAPI 확장 등록: %credit_balance% / %credit_balance_formatted%");
        } catch (Throwable t) {
            getComponentLogger().warn("PlaceholderAPI 확장 등록 실패(무시): " + t.getMessage());
        }
    }

    private Messages loadMessages(FileConfiguration config) {
        File file = new File(getDataFolder(), "messages.yml");
        FileConfiguration messagesConfig = YamlConfiguration.loadConfiguration(file);
        String suffix = config.getString("display.suffix", "원");
        boolean comma = config.getBoolean("display.thousands-separator", true);
        return new Messages(messagesConfig, suffix, comma);
    }

    private void saveDefaultResource(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
    }

    private void disableSelf() {
        // 깨진 DB 에 쓰지 않도록 크레딧 연산을 수행하지 않고 비활성화(fail-safe).
        getServer().getPluginManager().disablePlugin(this);
    }

    private static ThreadFactory namedDaemonFactory() {
        AtomicInteger idx = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, "크레딧-async-" + idx.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
