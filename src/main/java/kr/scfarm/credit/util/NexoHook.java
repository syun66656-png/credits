package kr.scfarm.credit.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Nexo 의 MiniMessage 파서({@code com.nexomc.nexo.utils.AdventureUtils#parseMiniMessage})를
 * <b>리플렉션</b>으로 호출해, {@code <glyph:...>}·{@code <shift:...>} 등 Nexo 태그가 우리 메세지에서
 * 그대로 렌더되게 한다.
 *
 * <p>리플렉션을 쓰는 이유: 이 개발 환경에서 Nexo 저장소/문서가 차단되어 컴파일용 API 시그니처를 확정할
 * 수 없다. 리플렉션 + 안전 폴백이면 컴파일 의존성이 없어 빌드가 깨지지 않고, Nexo 미설치/버전 상이 시에도
 * 조용히 폴백한다({@code null} 반환 → 호출측이 자체 MiniMessage 로 처리).
 *
 * <p>모든 Nexo 태그는 Nexo 가 처리하므로, 우리 자체 {@code <glyph>}(messages.yml 의 glyphs 섹션)는
 * Nexo 가 설치된 서버에서는 사용되지 않는다(Nexo 글리프가 우선).
 */
final class NexoHook {

    private static final Method PARSE = resolveMethod();

    private NexoHook() {
    }

    private static Method resolveMethod() {
        try {
            Class<?> cls = Class.forName("com.nexomc.nexo.utils.AdventureUtils");
            Method best = null;
            int bestScore = -1;
            for (Method m : cls.getMethods()) {
                if (!m.getName().equals("parseMiniMessage") || !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (!Component.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 1 || p[0] != String.class) {
                    continue;
                }
                int score;
                if (p.length == 2 && p[1] == TagResolver[].class) {
                    score = 3;                       // parseMiniMessage(String, TagResolver...)
                } else if (p.length == 2 && p[1] == TagResolver.class) {
                    score = 2;                       // parseMiniMessage(String, TagResolver)
                } else if (p.length == 1) {
                    score = 1;                       // parseMiniMessage(String)
                } else {
                    continue;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = m;
                }
            }
            return best;
        } catch (Throwable t) {
            return null; // Nexo 미설치 등
        }
    }

    /** Nexo 파서를 사용할 수 있는가. */
    static boolean available() {
        return PARSE != null;
    }

    /**
     * Nexo MiniMessage 로 파싱. 성공 시 Component, 실패/불가 시 {@code null}(호출측이 폴백).
     */
    static Component parse(String text, TagResolver... resolvers) {
        if (PARSE == null) {
            return null;
        }
        try {
            Class<?>[] p = PARSE.getParameterTypes();
            Object result;
            if (p.length == 1) {
                result = PARSE.invoke(null, text);
            } else if (p[1] == TagResolver[].class) {
                result = PARSE.invoke(null, text, resolvers);
            } else {
                result = PARSE.invoke(null, text, TagResolver.resolver(resolvers)); // 여러 개를 하나로 합쳐 전달
            }
            return (result instanceof Component) ? (Component) result : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
