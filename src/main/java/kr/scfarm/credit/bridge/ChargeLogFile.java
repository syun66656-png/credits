package kr.scfarm.credit.bridge;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 자동충전 지급 로그를 <b>플러그인 폴더 안 yml 파일</b>로도 남긴다(분쟁 방지 보조 기록).
 *
 * <p>위치: {@code plugins/Credit/charge-logs/charges-YYYY-MM.yml} (월별 파일).
 * DB(credit_processed_charge/ledger) 와 서버 로그에 더해, 사람이 바로 열어볼 수 있는 append-only yml.
 *
 * <p>실제 지급이 일어나는 <b>브릿지 서버에서만</b> 기록된다(다른 백엔드는 지급을 처리하지 않으므로 남길 게 없다).
 * 브릿지 폴링(비동기 스레드)에서 호출되며 Bukkit API 를 쓰지 않는다. append 전용이라 전체 재작성이 없다.
 */
public final class ChargeLogFile {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File dir;
    private final ComponentLogger logger;
    private final Object lock = new Object();

    public ChargeLogFile(File dataFolder, ComponentLogger logger) {
        this.dir = new File(dataFolder, "charge-logs");
        this.logger = logger;
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warn("지급 로그 폴더 생성 실패: " + dir.getAbsolutePath());
        }
    }

    /** 지급 1건을 월별 yml 파일에 한 항목(YAML 시퀀스 블록)으로 append 한다. */
    public void append(String chargeId, UUID uuid, String nickname, long amount, long before, long after) {
        ZonedDateTime now = ZonedDateTime.now(); // 서버 로컬 타임존
        File file = new File(dir, "charges-" + now.format(MONTH) + ".yml");

        String block = "- charge_id: \"" + escape(chargeId) + "\"\n"
                + "  uuid: \"" + uuid + "\"\n"
                + "  nickname: \"" + escape(nickname == null ? "" : nickname) + "\"\n"
                + "  amount: " + amount + "\n"
                + "  balance_before: " + before + "\n"
                + "  balance_after: " + after + "\n"
                + "  time: \"" + now.format(STAMP) + "\"\n";

        synchronized (lock) {
            try (FileWriter w = new FileWriter(file, StandardCharsets.UTF_8, true)) {
                w.write(block);
            } catch (IOException e) {
                logger.warn("지급 로그 파일 기록 실패: " + file.getName() + " (" + e.getMessage() + ")");
            }
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
