package kr.scfarm.credit.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.configuration.ConfigurationSection;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 교차서버 캐시 무효화(선택). 잔액 변경을 Redis Pub/Sub 로 브로드캐스트해 다른 서버의 placeholder 캐시를 무효화한다.
 *
 * <p>기본 설계는 MariaDB 단일 진실원 + 매 조회 DB 라 캐시가 없어도 정확하다. 이 매니저는 성능 최적화이며,
 * 끄면(또는 lettuce 미탑재) 캐시 없이 항상 DB 를 조회하는 폴백으로 동작한다(무손실/정확 우선).
 *
 * <p>lettuce-core 를 참조하므로, 이 클래스가 로드/사용되려면 plugin.yml 의 libraries 에 lettuce 가 있어야 한다.
 * 없으면 호출측이 {@code NoClassDefFoundError} 를 잡아 Redis 없이 진행한다.
 */
public final class RedisManager {

    private final String host;
    private final int port;
    private final String password;
    private final String channel;
    private final ComponentLogger logger;
    private final Consumer<UUID> onInvalidate;

    private RedisClient client;
    private StatefulRedisConnection<String, String> pubConn;
    private StatefulRedisPubSubConnection<String, String> subConn;

    public RedisManager(ConfigurationSection redisConfig, ComponentLogger logger, Consumer<UUID> onInvalidate) {
        this.host = redisConfig.getString("host", "127.0.0.1");
        this.port = redisConfig.getInt("port", 6379);
        this.password = redisConfig.getString("password", "");
        this.channel = redisConfig.getString("channel", "credit:invalidate");
        this.logger = logger;
        this.onInvalidate = onInvalidate;
    }

    /** 연결 + 구독 시작. 성공 여부 반환. */
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
                    if (!channel.equals(ch)) {
                        return;
                    }
                    try {
                        onInvalidate.accept(UUID.fromString(message.trim()));
                    } catch (IllegalArgumentException ignored) {
                        // 잘못된 페이로드는 무시
                    }
                }
            });
            this.subConn.sync().subscribe(channel);
            return true;
        } catch (Exception e) {
            logger.warn("Redis 연결 실패 — 캐시 없이 DB 조회로 폴백합니다.", e);
            close();
            return false;
        }
    }

    /** 잔액 변경 브로드캐스트(비동기, 실패해도 크레딧 연산엔 영향 없음). */
    public void publishInvalidate(UUID uuid) {
        try {
            if (pubConn != null) {
                pubConn.async().publish(channel, uuid.toString());
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
