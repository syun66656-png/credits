package kr.scfarm.credit.util;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * messages.yml 기반 표시 문구 렌더링.
 *
 * <p>전부 MiniMessage 템플릿이며, 우리 컨벤션대로 커스텀 글리프 태그 {@code <glyph:이름>} 를 지원한다.
 * 플레이스홀더: {@code <amount>}(콤마 포맷 적용값), {@code <suffix>}, {@code <player>}, {@code <uuid>}.
 */
public final class Messages {

    private final MiniMessage mini;
    private final Map<String, String> raw = new HashMap<>();
    private final String suffix;
    private final boolean thousandsSeparator;

    public Messages(FileConfiguration messagesConfig, String suffix, boolean thousandsSeparator) {
        this.suffix = suffix;
        this.thousandsSeparator = thousandsSeparator;

        for (String key : messagesConfig.getKeys(false)) {
            if (messagesConfig.isString(key)) {
                raw.put(key, messagesConfig.getString(key));
            }
        }

        // 커스텀 글리프 태그: <glyph:이름> → glyphs.<이름> 에 정의된 "문자[|폰트키]" 를 삽입.
        // 정의되지 않은 이름은 빈 컴포넌트로 처리해 파싱 실패를 막는다.
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

    /** 플레이스홀더 없이 키를 렌더링. */
    public Component get(String key) {
        return render(key);
    }

    /** {@code <amount>} + {@code <suffix>} 만 채워 렌더링. */
    public Component amount(String key, long amount) {
        return render(key,
                Placeholder.unparsed("amount", formatAmount(amount)),
                Placeholder.unparsed("suffix", suffix));
    }

    /** {@code <player>} + {@code <amount>} + {@code <suffix>} 를 채워 렌더링. */
    public Component playerAmount(String key, String player, long amount) {
        return render(key,
                Placeholder.unparsed("player", player),
                Placeholder.unparsed("amount", formatAmount(amount)),
                Placeholder.unparsed("suffix", suffix));
    }

    /** {@code <player>} 만 채워 렌더링. */
    public Component player(String key, String player) {
        return render(key, Placeholder.unparsed("player", player));
    }

    /** {@code <uuid>} + {@code <player>} 를 채워 렌더링(인증 결과). */
    public Component verify(String key, UUID uuid, String player) {
        return render(key,
                Placeholder.unparsed("uuid", uuid.toString()),
                Placeholder.unparsed("player", player == null ? "" : player));
    }

    public Component render(String key, TagResolver... resolvers) {
        String template = raw.get(key);
        if (template == null) {
            return Component.text("<missing message: " + key + ">");
        }
        return mini.deserialize(template, resolvers);
    }
}
