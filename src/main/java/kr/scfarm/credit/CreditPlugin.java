package kr.scfarm.credit;

import kr.scfarm.credit.api.CreditAPI;
import kr.scfarm.credit.bridge.HomepageBridge;
import kr.scfarm.credit.command.CreditCommand;
import kr.scfarm.credit.db.CreditDao;
import kr.scfarm.credit.db.DatabaseManager;
import kr.scfarm.credit.impl.CreditService;
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

        // 5) 크레딧 서비스 + 공개 API 등록(ServicesManager)
        CreditDao dao = new CreditDao(database);
        this.creditService = new CreditService(dao, executor, clog, redis);
        getServer().getServicesManager().register(CreditAPI.class, creditService, this, ServicePriority.Normal);

        // 6) 메세지 + 이름 리졸버 + 명령어
        Messages messages = loadMessages(config);
        NameResolver names = new NameResolver(executor, clog);
        CreditCommand command = new CreditCommand(creditService, messages, names);
        if (getCommand("크레딧") != null) {
            getCommand("크레딧").setExecutor(command);
            getCommand("크레딧").setTabCompleter(command);
        } else {
            getComponentLogger().warn("plugin.yml 에 '크레딧' 명령어가 등록되지 않았습니다.");
        }

        // 7) (선택) PlaceholderAPI
        registerPlaceholders(messages);

        // 8) 홈페이지 결제 브릿지(비동기 폴링)
        startHomepageBridge(config, dao);

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

    private void startHomepageBridge(FileConfiguration config, CreditDao dao) {
        ConfigurationSection hp = config.getConfigurationSection("homepage");
        if (hp == null) {
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

        HomepageBridge bridge = new HomepageBridge(baseUrl, key, dao, getComponentLogger());
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
