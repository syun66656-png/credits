package kr.scfarm.credit;

import kr.scfarm.credit.api.CreditAPI;
import kr.scfarm.credit.bridge.ChargeLogFile;
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
import java.util.UUID;
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
    private Messages messages;
    private CreditPlaceholderExpansion placeholderExpansion;

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
        NameResolver names = new NameResolver(executor, dao, clog);
        this.messages = loadMessages(config);

        // 4) (선택) Redis 교차서버 Pub/Sub — 캐시 무효화 + 자동충전 지급 알림 전파.
        //    지급 알림 브로드캐스트를 받으면 "그 유저가 이 서버에 접속 중일 때만" 인게임 알림을 보낸다.
        //    → 브릿지 서버가 1대여도 유저가 어느 백엔드에 있든 알림 도달. 플러그인 없는 서버(hub)는 자동 제외.
        this.redis = null;
        ConfigurationSection redisSec = config.getConfigurationSection("redis");
        if (redisSec != null && redisSec.getBoolean("enabled", false)) {
            try {
                RedisManager rm = new RedisManager(redisSec, clog,
                        uuid -> {
                            if (creditService != null) {
                                creditService.invalidateLocal(uuid);
                            }
                        },
                        this::deliverChargeNotification);
                boolean ok = rm.connect();
                console.logConn("Redis", ok);
                this.redis = ok ? rm : null;
            } catch (Throwable t) {
                // lettuce 미탑재(NoClassDefFoundError) 등 → 캐시 없이 진행
                console.logConn("Redis", false);
                getComponentLogger().warn("Redis 를 사용할 수 없어 캐시 없이 DB 조회로 진행합니다("
                        + t.getClass().getSimpleName() + "). 지급/기록에는 영향이 없습니다.");
                this.redis = null;
            }
        }

        // 5) 접속/퇴장 캐시 리스너 + 크레딧 서비스 + 공개 API 등록(ServicesManager)
        //    placeholder 캐시는 온라인 유저만 담는다(무한 증가 방지 — PlayerPoints 캐시 수명주기 반영).
        final PlayerCacheListener[] listenerRef = new PlayerCacheListener[1];
        this.creditService = new CreditService(dao, executor, clog, redis,
                uuid -> listenerRef[0] != null && listenerRef[0].onlineUuids().contains(uuid));
        PlayerCacheListener cacheListener = new PlayerCacheListener(creditService, names);
        listenerRef[0] = cacheListener;
        getServer().getPluginManager().registerEvents(cacheListener, this);
        getServer().getServicesManager().register(CreditAPI.class, creditService, this, ServicePriority.Normal);

        // 6) 명령어
        CreditCommand command = new CreditCommand(this, creditService, messages, names);
        if (getCommand("크레딧") != null) {
            getCommand("크레딧").setExecutor(command);
            getCommand("크레딧").setTabCompleter(command);
        } else {
            getComponentLogger().warn("plugin.yml 에 '크레딧' 명령어가 등록되지 않았습니다.");
        }

        // 7) (선택) PlaceholderAPI
        registerPlaceholders(messages);

        // 8) 홈페이지 결제 브릿지(비동기 폴링). 지급 성공 시 캐시/Redis 통지 + 인게임 알림 브로드캐스트.
        startHomepageBridge(config, dao);

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

        // PlaceholderAPI 확장 해제. persist()=true 라 직접 해제하지 않으면 PAPI 가 죽은 서비스/
        // 클래스로더를 계속 붙들고, 재로드 후 %credit_balance% 가 종료된 스레드풀을 건드린다.
        if (placeholderExpansion != null) {
            try {
                placeholderExpansion.unregister();
            } catch (Throwable ignored) {
                // PAPI 가 이미 내렸을 수 있음
            }
            placeholderExpansion = null;
        }

        // Redis 를 먼저 닫는다 — 구독 스레드가 비활성화된 플러그인의 스케줄러를 건드리면 예외가 난다.
        if (redis != null) {
            redis.close();
        }

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    // 강제 종료 후에도 진행 중인 트랜잭션이 끝날 시간을 준다
                    // (커넥션 풀을 먼저 닫아 커밋이 중단되는 것을 방지)
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
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
        // 벨로시티 다중 백엔드에서는 브릿지 폴링을 "한 서버에서만" 켜야 한다.
        // 그래야 (1) 홈페이지 API 를 서버 수만큼 중복 폴링하지 않고,
        //        (2) [크레딧 자동충전] 감사 로그가 항상 그 한 서버의 로그 파일에만 남아 추적이 쉽다.
        // (지급 자체는 charge_id PK 멱등이라 여러 서버가 켜져도 중복 지급은 없지만, 로그가 흩어진다.)
        // 기본값 false: 켜야만 도는 opt-in. 기본 true 로 두면 신규 설치 시 전 백엔드가 동시에 폴링해
        // 감사 로그가 흩어지고 API 부하가 서버 수만큼 늘어난다(문서의 "한 서버만" 지침과도 충돌).
        if (!hp.getBoolean("bridge-enabled", false)) {
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
        baseUrl = baseUrl.trim();
        key = key.trim();
        // HTTP 헤더에 넣을 수 없는 문자가 키에 섞이면 요청 생성 자체가 예외를 던지고, 그 예외 메세지에는
        // 키 전체가 담긴다 → 로그 파일에 비밀키가 그대로 남는다. 시작 시 검증해 원천 차단한다.
        if (!key.matches("[\\x21-\\x7E]+")) {
            getComponentLogger().error("홈페이지 브릿지: plugin-key 에 공백/한글/보이지 않는 문자가 섞여 있습니다. "
                    + "복사 과정에서 들어간 문자를 제거하세요. (보안상 키 값은 로그에 남기지 않습니다) → 폴링 중단");
            return;
        }
        if (!baseUrl.startsWith("https://")) {
            // http 면 첫 요청부터 비밀키가 평문으로 전송된다.
            getComponentLogger().error("홈페이지 브릿지: base-url 은 반드시 https:// 여야 합니다(현재 설정은 평문 전송 위험). → 폴링 중단");
            return;
        }
        try {
            java.net.URI u = java.net.URI.create(baseUrl);
            if (u.getHost() == null) {
                throw new IllegalArgumentException("host 없음");
            }
        } catch (Exception e) {
            getComponentLogger().error("홈페이지 브릿지: base-url 형식이 올바르지 않습니다 → 폴링 중단");
            return;
        }

        // 지급 로그 yml 파일(플러그인 폴더). homepage.charge-log-file: false 로 끌 수 있음(기본 켜짐).
        ChargeLogFile chargeLog = hp.getBoolean("charge-log-file", true)
                ? new ChargeLogFile(getDataFolder(), getComponentLogger())
                : null;

        HomepageBridge bridge = new HomepageBridge(baseUrl, key, dao, getComponentLogger(),
                (uuid, credits) -> {
                    // 캐시 무효화 + (Redis 시) 교차서버 무효화 브로드캐스트
                    creditService.notifyExternalChange(uuid);
                    // 지급 알림: Redis 가 있으면 전 백엔드로 브로드캐스트(자기 포함) → 유저가 접속한 서버가 전달.
                    //           없으면 브릿지 서버 로컬로만 전달(폴백).
                    if (redis != null) {
                        redis.publishChargeNotify(uuid, credits);
                    } else {
                        deliverChargeNotification(uuid, credits);
                    }
                }, chargeLog);
        // Paper 비동기 스케줄러: 메인 스레드를 절대 막지 않는다.
        getServer().getAsyncScheduler().runAtFixedRate(
                this,
                task -> bridge.pollOnce(),
                Math.min(10, interval), // 최초 지연(초)
                interval,
                TimeUnit.SECONDS);
        getComponentLogger().info("홈페이지 브릿지: " + interval + "초 주기 폴링 시작.");
        // 지급 알림 도달 범위를 콘솔에 명시(다중 백엔드 진단용).
        if (redis != null) {
            getComponentLogger().info("지급 알림: Redis 브로드캐스트 활성 → 유저가 접속한 어느 백엔드든 알림 전달.");
        } else {
            getComponentLogger().warn("지급 알림: Redis 미사용 → 이 브릿지 서버 접속자에게만 알림. "
                    + "다른 백엔드에서도 알림을 받으려면 모든 서버에서 redis.enabled: true 로 설정하세요.");
        }
    }

    /**
     * 자동충전 지급 인게임 알림 전달. 브릿지 async 스레드 또는 Redis 구독 스레드에서 호출될 수 있으므로
     * 실제 전송은 메인 스레드로 디스패치한다. {@code getPlayer(uuid)} 가 이 서버 접속자만 반환하므로,
     * 네트워크 전체에서 유저가 접속한 바로 그 백엔드 1대만 실제로 알림을 보낸다(플러그인 없는 hub 는 자동 제외).
     *
     * @param credits 지급된 크레딧 수량 (결제 금액이 아니다 — 1,000원 = 1크레딧)
     */
    private void deliverChargeNotification(UUID uuid, long credits) {
        if (!isEnabled()) {
            return; // 비활성화 상태에서 스케줄러를 쓰면 IllegalPluginAccessException
        }
        getServer().getGlobalRegionScheduler().execute(this, () -> {
            org.bukkit.entity.Player p = getServer().getPlayer(uuid);
            if (p != null && messages != null) {
                // 메인 스레드 → PAPI(%...%) 안전. <player> = 지급 대상 닉네임. 빈 메세지면 null → 전송 생략.
                net.kyori.adventure.text.Component c =
                        messages.playerAmount("charge-received", p, p.getName(), credits);
                if (c != null) {
                    p.sendMessage(c);
                }
            }
        });
    }

    private void registerPlaceholders(Messages messages) {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            CreditPlaceholderExpansion exp = new CreditPlaceholderExpansion(creditService, messages);
            exp.register();
            this.placeholderExpansion = exp; // onDisable 에서 해제하기 위해 보관
            getComponentLogger().info("PlaceholderAPI 확장 등록: %credit_balance% / %credit_balance_formatted%");
        } catch (Throwable t) {
            getComponentLogger().warn("PlaceholderAPI 확장 등록 실패(무시): " + t.getMessage());
        }
    }

    /**
     * {@code /크레딧 리로드}: config.yml 의 표시 설정 + messages.yml 문구를 다시 읽어 즉시 반영한다.
     * <b>메세지/표시 설정만</b> 재적재한다 — DB/Redis/브릿지 폴링 등 연결 설정은 안전을 위해 재시작이 필요하다
     * (커넥션 풀·구독을 런타임에 갈아끼우다 유실이 나는 것보다, 재시작이 무손실 원칙에 맞다).
     * 메인 스레드에서 호출된다(명령어 핸들러).
     */
    public void reloadMessages() throws Exception {
        reloadConfig();
        FileConfiguration config = getConfig();
        // 파일이 삭제됐으면 기본 파일을 복구한 뒤 읽는다.
        saveDefaultResource("messages.yml");
        // 엄격 파싱: YamlConfiguration.loadConfiguration 은 문법 오류 시 조용히 "빈 설정"을 돌려주기 때문에
        // 오타 하나로 모든 문구가 <missing message:...> 가 되고, 심지어 성공 메세지까지 깨진다.
        // load(File) 은 예외를 던지므로 호출측이 reload-failed 를 띄우고 기존 문구를 그대로 유지할 수 있다.
        YamlConfiguration messagesConfig = new YamlConfiguration();
        messagesConfig.load(new File(getDataFolder(), "messages.yml"));
        applyMessageDefaults(messagesConfig);
        String suffix = config.getString("display.suffix", "원");
        boolean comma = config.getBoolean("display.thousands-separator", true);
        messages.reload(messagesConfig, suffix, comma);
    }

    private Messages loadMessages(FileConfiguration config) {
        File file = new File(getDataFolder(), "messages.yml");
        FileConfiguration messagesConfig = YamlConfiguration.loadConfiguration(file);
        applyMessageDefaults(messagesConfig);
        String suffix = config.getString("display.suffix", "원");
        boolean comma = config.getBoolean("display.thousands-separator", true);
        // PlaceholderAPI(softdepend)가 설치돼 있으면 메세지에서 %...% 를 해석한다.
        boolean papi = getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        // Nexo(softdepend)가 설치돼 있으면 메세지를 Nexo MiniMessage 로 파싱해 <glyph>/<shift> 등을 지원한다.
        boolean nexo = getServer().getPluginManager().getPlugin("Nexo") != null;
        return new Messages(messagesConfig, suffix, comma, papi, nexo);
    }

    /**
     * jar 안의 messages.yml 을 기본값으로 얹는다.
     *
     * <p>이게 없으면 <b>업데이트한 서버에서 새로 추가된 키가 통째로 비어</b> 플레이어에게
     * {@code <missing message: charge-received>} 같은 디버그 문자열이 그대로 노출된다
     * (기존 파일은 덮어쓰지 않으므로 새 키가 영원히 없다). copyDefaults 를 켜야
     * {@code getKeys(false)} 가 기본값 키까지 포함한다.
     */
    private void applyMessageDefaults(FileConfiguration messagesConfig) {
        try (java.io.InputStream in = getResource("messages.yml")) {
            if (in == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            messagesConfig.setDefaults(defaults);
            messagesConfig.options().copyDefaults(true);
        } catch (Exception e) {
            getComponentLogger().warn("messages.yml 기본값 적용 실패(누락 키가 있을 수 있습니다): " + e.getMessage());
        }
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
