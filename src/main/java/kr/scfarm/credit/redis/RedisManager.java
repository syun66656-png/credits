package kr.scfarm.credit.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.configuration.ConfigurationSection;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 교차서버 Redis Pub/Sub(선택). 두 종류의 이벤트를 브로드캐스트한다:
 * <ul>
 *   <li><b>invalidate 채널</b> — 잔액 변경 시 다른 서버의 placeholder 캐시를 무효화(스테일 방지).</li>
 *   <li><b>notify 채널</b> — 홈페이지 자동충전 지급 이벤트({@code uuid,credits}). 각 백엔드가 받아
 *       "그 유저가 자기 서버에 접속 중이면" 인게임 알림을 보낸다. 브릿지 서버가 1대여도 유저가 어느
 *       백엔드에 있든 알림이 도달한다. 플러그인이 없는 서버(예: hub)는 구독자가 없어 자동 제외된다.</li>
 * </ul>
 *
 * <p>기본 설계는 MariaDB 단일 진실원 + 매 조회 DB 라 캐시가 없어도 정확하다. 이 매니저는 최적화/편의이며,
 * 끄면(또는 lettuce 미탑재) 캐시 없이 DB 조회로 폴백하고 알림은 브릿지 서버 로컬로만 폴백한다.
 *
 * <p>lettuce-core 를 참조하므로, 이 클래스가 로드/사용되려면 plugin.yml 의 libraries 에 lettuce 가 있어야 한다.
 * 없으면 호출측이 {@code NoClassDefFoundError} 를 잡아 Redis 없이 진행한다.
 */
public final class RedisManager {

    private final String host;
    private final int port;
    private final String password;
    private final String invalidateChannel;
    private final String notifyChannel;
    private final ComponentLogger logger;
    private final Consumer<UUID> onInvalidate;
    private final BiConsumer<UUID, Long> onNotify; // (uuid, 지급 크레딧 수량)

    private RedisClient client;
    private StatefulRedisConnection<String, String> pubConn;
    private StatefulRedisPubSubConnection<String, String> subConn;

    public RedisManager(ConfigurationSection redisConfig, ComponentLogger logger,
                        Consumer<UUID> onInvalidate, BiConsumer<UUID, Long> onNotify) {
        this.host = redisConfig.getString("host", "127.0.0.1");
        this.port = redisConfig.getInt("port", 6379);
        this.password = redisConfig.getString("password", "");
        this.invalidateChannel = redisConfig.getString("channel", "credit:invalidate");
        this.notifyChannel = redisConfig.getString("notify-channel", "credit:notify");
        this.logger = logger;
        this.onInvalidate = onInvalidate;
        this.onNotify = onNotify;
    }

    /** 연결 + 두 채널 구독 시작. 성공 여부 반환. */
    public boolean connect() {
        try {
            RedisURI.Builder uri = RedisURI.builder().withHost(host).withPort(port);
            if (password != null && !password.isEmpty()) {
                uri.withPassword(password.toCharArray());
            }
            this.client = RedisClient.create(uri.build());
            this.pubConn = client.connect();
            this.subConn = client.connectPubSub();
            this.subConn.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String ch, String message) {
                    handleMessage(ch, message);
                }
            });
            this.subConn.sync().subscribe(invalidateChannel, notifyChannel);
            return true;
        } catch (Exception e) {
            logger.warn("Redis 연결 실패 — 캐시 없이 DB 조회로 폴백합니다.", e);
            close();
            return false;
        }
    }

    private void handleMessage(String ch, String message) {
        if (invalidateChannel.equals(ch)) {
            try {
                onInvalidate.accept(UUID.fromString(message.trim()));
            } catch (IllegalArgumentException ignored) {
                // 잘못된 페이로드는 무시
            }
        } else if (notifyChannel.equals(ch)) {
            // 페이로드 형식: "uuid,amount"
            try {
                int comma = message.indexOf(',');
                if (comma <= 0) {
                    return;
                }
                UUID uuid = UUID.fromString(message.substring(0, comma).trim());
                long credits = Long.parseLong(message.substring(comma + 1).trim());
                onNotify.accept(uuid, credits);
            } catch (Exception ignored) {
                // 잘못된 페이로드는 무시
            }
        }
    }

    /** 잔액 변경 브로드캐스트(비동기, 실패해도 크레딧 연산엔 영향 없음). */
    public void publishInvalidate(UUID uuid) {
        publish(invalidateChannel, uuid.toString());
    }

    /** 자동충전 지급 알림 브로드캐스트(비동기). 각 백엔드가 받아 자기 서버 접속자에게 인게임 알림. */
    public void publishChargeNotify(UUID uuid, long credits) {
        publish(notifyChannel, uuid.toString() + "," + credits);
    }

    private void publish(String channel, String payload) {
        try {
            if (pubConn != null) {
                pubConn.async().publish(channel, payload);
            }
        } catch (Exception e) {
            logger.warn("Redis 브로드캐스트 실패(무시)", e);
        }
    }

    public void close() {
        try {
            if (subConn != null) {
                subConn.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (pubConn != null) {
                pubConn.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (client != null) {
                client.shutdown();
            }
        } catch (Exception ignored) {
        }
    }
}
