package kr.scfarm.credit.util;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * messages.yml 기반 표시 문구 렌더링. <b>플레이어에게 나가는 모든 문구는 여기(messages.yml)에서 수정 가능</b>하다.
 *
 * <p>지원 기능:
 * <ul>
 *   <li><b>MiniMessage</b> — {@code <gold>}, {@code <gradient>}, {@code <hover>} 등 전체 태그.</li>
 *   <li><b>내부 플레이스홀더</b> — {@code <amount>}(콤마 포맷), {@code <suffix>}, {@code <player>}, {@code <uuid>}.</li>
 *   <li><b>커스텀 글리프</b> — {@code <glyph:이름>}.</li>
 *   <li><b>PlaceholderAPI</b> — {@code %player_name%}, {@code %credit_balance%} 등 {@code %...%}.
 *       PAPI 가 설치돼 있고 플레이어 컨텍스트가 주어질 때, MiniMessage 파싱 <i>전에</i> 해석된다.
 *       (PAPI 확장이 메인 스레드를 가정할 수 있어, 컨텍스트를 넘기는 호출은 메인 스레드에서 해야 한다.)</li>
 *   <li><b>Nexo shift</b> — {@code <shift:-2>} 처럼 쓰면 내부적으로 {@code %nexo_shift_-2%} 로 바꿔
 *       PlaceholderAPI 를 통해 Nexo 가 실제 shift 유니코드로 치환한다. (PlaceholderAPI + Nexo 설치 필요)
 *       우리 파서엔 {@code <shift>} 태그가 없어서, Nexo 가 확실히 제공하는 shift 플레이스홀더로 우회한다.</li>
 * </ul>
 */
public final class Messages {

    // Nexo 의 <shift:N> MiniMessage 태그 → PlaceholderAPI %nexo_shift_N% 로 변환(우리 파서엔 shift 태그가 없음).
    private static final Pattern NEXO_SHIFT = Pattern.compile("<shift:(-?\\d+)>");

    // 아래 3개는 /크레딧 리로드 로 갱신되므로 non-final. papiEnabled 는 런타임에 안 바뀐다(PAPI 설치 여부).
    private volatile MiniMessage mini;
    private volatile Map<String, String> raw;
    private volatile String suffix;
    private volatile boolean thousandsSeparator;
    private final boolean papiEnabled;
    private final boolean nexoEnabled;

    public Messages(FileConfiguration messagesConfig, String suffix, boolean thousandsSeparator,
                    boolean papiEnabled, boolean nexoEnabled) {
        this.papiEnabled = papiEnabled;
        // Nexo 가 설치돼 있고 리플렉션으로 파서를 찾았을 때만 Nexo 파싱 경로를 쓴다.
        this.nexoEnabled = nexoEnabled && NexoHook.available();
        load(messagesConfig, suffix, thousandsSeparator);
    }

    /** messages.yml + 표시설정을 다시 읽어 in-place 갱신한다(/크레딧 리로드). 모든 참조가 즉시 반영된다. */
    public void reload(FileConfiguration messagesConfig, String suffix, boolean thousandsSeparator) {
        load(messagesConfig, suffix, thousandsSeparator);
    }

    private void load(FileConfiguration messagesConfig, String suffix, boolean thousandsSeparator) {
        this.suffix = suffix;
        this.thousandsSeparator = thousandsSeparator;

        Map<String, String> newRaw = new HashMap<>();
        for (String key : messagesConfig.getKeys(false)) {
            if (messagesConfig.isString(key)) {
                newRaw.put(key, messagesConfig.getString(key));
            }
        }
        this.raw = newRaw;

        // 커스텀 글리프 태그: <glyph:이름> → glyphs.<이름> 에 정의된 "문자[|폰트키]" 를 삽입.
        Map<String, Component> glyphs = new HashMap<>();
        ConfigurationSection glyphSec = messagesConfig.getConfigurationSection("glyphs");
        if (glyphSec != null) {
            for (String name : glyphSec.getKeys(false)) {
                String def = glyphSec.getString(name, "");
                Component c;
                int bar = def.indexOf('|');
                if (bar >= 0) {
                    c = Component.text(def.substring(0, bar)).font(Key.key(def.substring(bar + 1)));
                } else {
                    c = Component.text(def);
                }
                glyphs.put(name, c);
            }
        }
        TagResolver glyphResolver = TagResolver.resolver("glyph", (args, ctx) -> {
            String name = args.popOr("glyph 태그에는 이름 인자가 필요합니다").value();
            return Tag.inserting(glyphs.getOrDefault(name, Component.empty()));
        });

        this.mini = MiniMessage.builder()
                .editTags(b -> b.resolver(glyphResolver))
                .build();
    }

    /** 금액을 표시 규칙(콤마 여부)에 맞춰 문자열로 변환한다. */
    public String formatAmount(long amount) {
        if (thousandsSeparator) {
            return NumberFormat.getNumberInstance(Locale.KOREA).format(amount);
        }
        return Long.toString(amount);
    }

    public String suffix() {
        return suffix;
    }

    // ── 편의 렌더러 (papi = PlaceholderAPI 컨텍스트, null 이면 %...% 미해석) ────

    /** 플레이스홀더 없이 키를 렌더링. */
    public Component get(String key) {
        return render(key, null);
    }

    public Component get(String key, OfflinePlayer papi) {
        return render(key, papi);
    }

    /** {@code <amount>} + {@code <suffix>} 를 채워 렌더링. */
    public Component amount(String key, long amount) {
        return amount(key, null, amount);
    }

    public Component amount(String key, OfflinePlayer papi, long amount) {
        return render(key, papi,
                Placeholder.unparsed("amount", formatAmount(amount)),
                Placeholder.unparsed("suffix", suffix));
    }

    /** {@code <player>} + {@code <amount>} + {@code <suffix>} 를 채워 렌더링. */
    public Component playerAmount(String key, String player, long amount) {
        return playerAmount(key, null, player, amount);
    }

    public Component playerAmount(String key, OfflinePlayer papi, String player, long amount) {
        return render(key, papi,
                Placeholder.unparsed("player", player == null ? "" : player),
                Placeholder.unparsed("amount", formatAmount(amount)),
                Placeholder.unparsed("suffix", suffix));
    }

    /** {@code <player>} + {@code <amount>} + {@code <suffix>} + {@code <uuid>} 를 채워 렌더링(지급/차감 확인용). */
    public Component playerAmountUuid(String key, OfflinePlayer papi, String player, long amount, UUID uuid) {
        return render(key, papi,
                Placeholder.unparsed("player", player == null ? "" : player),
                Placeholder.unparsed("amount", formatAmount(amount)),
                Placeholder.unparsed("suffix", suffix),
                Placeholder.unparsed("uuid", uuid == null ? "" : uuid.toString()));
    }

    /** {@code <player>} 만 채워 렌더링. */
    public Component player(String key, String player) {
        return render(key, null, Placeholder.unparsed("player", player == null ? "" : player));
    }

    /** {@code <uuid>} + {@code <player>} 를 채워 렌더링(인증 결과). */
    public Component verify(String key, UUID uuid, String player) {
        return verify(key, null, uuid, player);
    }

    public Component verify(String key, OfflinePlayer papi, UUID uuid, String player) {
        return render(key, papi,
                Placeholder.unparsed("uuid", uuid.toString()),
                Placeholder.unparsed("player", player == null ? "" : player));
    }

    /**
     * 핵심 렌더러. 키가 없으면 {@code <missing message: key>} 를, 값이 빈 문자열이면 {@code null}(전송 안 함)을 반환한다.
     * PAPI 컨텍스트가 있으면 {@code %...%} 및 {@code <shift:N>} 을 먼저 해석한 뒤 MiniMessage 로 파싱한다.
     * PAPI 해석 중 확장이 던지는 예외는 삼켜서(메세지 하나 때문에 흐름이 깨지지 않게) 원본 템플릿으로 진행한다.
     */
    public Component render(String key, OfflinePlayer papi, TagResolver... resolvers) {
        String template = raw.get(key);
        if (template == null) {
            return Component.text("<missing message: " + key + ">");
        }
        // 빈 문자열("")로 두면 해당 메세지를 완전히 끈다(전송 안 함). 예: unknown-command 를 조용히 처리.
        if (template.isBlank()) {
            return null;
        }
        if (papiEnabled && papi != null) {
            // Nexo 파서를 쓰지 않는 경우(예: Nexo 미설치 + PAPI 만)에도 shift 가 동작하도록,
            // <shift:N> → %nexo_shift_N% 로 치환한 뒤 PAPI 로 유니코드 해석(폴백 경로).
            template = NEXO_SHIFT.matcher(template).replaceAll("%nexo_shift_$1%");
            try {
                template = PapiHook.apply(papi, template);
            } catch (Throwable ignored) {
                // PAPI 확장 오류는 무시하고 원본 템플릿으로 진행
            }
        }
        // Nexo 설치 시: Nexo MiniMessage 로 파싱 → <glyph:...>·<shift:...> 등 Nexo 태그가 네이티브로 렌더된다.
        // (우리 <amount>/<suffix>/<player> 리졸버도 함께 전달. 실패 시 아래 자체 파서로 폴백)
        if (nexoEnabled) {
            Component nexo = NexoHook.parse(template, resolvers);
            if (nexo != null) {
                return nexo;
            }
        }
        return mini.deserialize(template, resolvers);
    }
}
