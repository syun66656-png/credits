package kr.scfarm.credit.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * 전 네트워크 플러그인 통일 콘솔 로그 포맷.
 *
 * <p>형식: <b>(흰색)플러그인명</b> <b>(회색) -</b> <b>(성공=주황 / 실패=빨강) 상태문구</b>
 * <br>예: {@code 크레딧 - MariaDB 연결 성공}
 *
 * <p>색상 상수를 한 곳에 모아 두어, 다른 플러그인과 쉽게 통일할 수 있게 한다.
 * Paper 의 Adventure {@link ComponentLogger} 로 찍으면 콘솔에 실제 색이 나온다.
 */
public final class ConsoleLog {

    /** 연결 성공 주황. 필요 시 hex 를 조정해 정확한 주황으로 맞춘다(예: 0xFF8C00). */
    public static final TextColor OK = TextColor.color(0xFFAA00);
    /** 연결 실패 빨강. */
    public static final TextColor FAIL = NamedTextColor.RED;
    /** 플러그인명(흰색). */
    public static final TextColor NAME = NamedTextColor.WHITE;
    /** 구분자(회색). */
    public static final TextColor SEP = NamedTextColor.GRAY;

    private static final String LABEL = "크레딧";

    private final ComponentLogger logger;

    public ConsoleLog(ComponentLogger logger) {
        this.logger = logger;
    }

    /**
     * 연결 상태 통일 로그. 예: {@code logConn("MariaDB", true)} → "크레딧 - MariaDB 연결 성공"(주황).
     *
     * @param target 연결 대상 표기(예: "MariaDB", "Redis")
     * @param ok     성공 여부
     */
    public void logConn(String target, boolean ok) {
        logger.info(
                Component.text(LABEL, NAME)
                        .append(Component.text(" - ", SEP))
                        .append(Component.text(target + (ok ? " 연결 성공" : " 연결 실패"), ok ? OK : FAIL))
        );
    }
}
