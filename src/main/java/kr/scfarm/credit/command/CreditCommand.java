package kr.scfarm.credit.command;

import kr.scfarm.credit.CreditPlugin;
import kr.scfarm.credit.impl.CreditService;
import kr.scfarm.credit.resolver.NameResolver;
import kr.scfarm.credit.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * {@code /크레딧} 명령어 처리 + 탭완성.
 *
 * <p>일반 유저는 {@code /크레딧}(본인 잔액)만 사용할 수 있다. 관리자 서브명령어(확인/지급/차감/인증)는
 * {@code credit.admin} 전용이며 권한이 없으면 탭완성/사용법에 노출되지 않는다.
 *
 * <p>Bukkit(online/usercache) 조회는 이 핸들러가 실행되는 <b>메인 스레드</b>에서만 수행하고,
 * DB/HTTP 는 비동기로 넘긴다. 완료 후 메세지 <b>빌드(PlaceholderAPI 해석 포함) + 전송</b>은
 * {@link #reply}(항상 메인 스레드)로 처리한다 — 일부 PAPI 확장이 메인 스레드를 가정하기 때문이다.
 */
public final class CreditCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_ADMIN = "credit.admin";
    private static final String PERM_USE = "credit.use";
    private static final List<String> SUBCOMMANDS = List.of("확인", "지급", "차감", "인증", "리로드");

    private final CreditPlugin plugin;
    private final CreditService credit;
    private final Messages msg;
    private final NameResolver names;

    public CreditCommand(CreditPlugin plugin, CreditService credit, Messages msg, NameResolver names) {
        this.plugin = plugin;
        this.credit = credit;
        this.msg = msg;
        this.names = names;
    }

    /** 메세지 빌드(PAPI 해석 포함) + 전송을 메인 스레드에서 수행. 비동기 콜백에서도 안전. 빈 메세지("")면 전송 생략. */
    private void reply(CommandSender sender, Supplier<Component> builder) {
        Runnable send = () -> {
            Component c = builder.get();
            if (c != null) { // 값이 "" 인 메세지는 null → 전송하지 않음
                sender.sendMessage(c);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            send.run();
        } else {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, send);
        }
    }

    /** 메세지를 읽는 대상(=sender)이 플레이어면 그 플레이어를 PAPI 컨텍스트로 쓴다(뷰어 기준 %...% 해석). */
    private static OfflinePlayer papiOf(CommandSender sender) {
        return sender instanceof Player p ? p : null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            handleSelf(sender);
            return true;
        }

        // 서브명령어는 전부 관리자 전용. 권한 없는(일반) 유저에게는 서브명령어가 "존재하지 않는 것처럼"
        // 처리한다 — "권한이 없습니다" 로 기능 존재를 노출하지 않고, 없는 명령어 문구를 보낸다.
        if (!sender.hasPermission(PERM_ADMIN)) {
            reply(sender, () -> msg.get("unknown-command", papiOf(sender)));
            return true;
        }

        String sub = args[0];
        switch (sub) {
            case "확인" -> handleCheck(sender, args);
            case "지급" -> handleGiveTake(sender, args, true);
            case "차감" -> handleGiveTake(sender, args, false);
            case "인증" -> handleVerify(sender, args);
            case "리로드" -> handleReload(sender);
            default -> reply(sender, () -> msg.get("usage-admin", papiOf(sender)));
        }
        return true;
    }

    // /크레딧 리로드 — config.yml(표시설정) + messages.yml 다시 읽기
    private void handleReload(CommandSender sender) {
        try {
            plugin.reloadMessages();
            reply(sender, () -> msg.get("reload-success", papiOf(sender)));
        } catch (Exception e) {
            plugin.getComponentLogger().warn("리로드 실패", e);
            reply(sender, () -> msg.get("reload-failed", papiOf(sender)));
        }
    }

    // /크레딧 — 본인 잔액
    private void handleSelf(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            reply(sender, () -> msg.get("player-only"));
            return;
        }
        if (!player.hasPermission(PERM_USE)) {
            reply(player, () -> msg.get("no-permission", player));
            return;
        }
        UUID uuid = player.getUniqueId();
        credit.getBalance(uuid).whenComplete((balance, ex) -> {
            if (ex != null) {
                reply(player, () -> msg.get("db-error", player));
            } else {
                reply(player, () -> msg.amount("balance-self", player, balance));
            }
        });
    }

    // /크레딧 확인 <닉네임>
    private void handleCheck(CommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, () -> msg.get("usage-admin", papiOf(sender)));
            return;
        }
        String nick = args[1];
        resolveUuid(nick).whenComplete((opt, ex) -> {
            if (ex != null || opt == null || opt.isEmpty()) {
                reply(sender, () -> msg.get("player-not-found", papiOf(sender)));
                return;
            }
            UUID uuid = opt.get();
            credit.getBalance(uuid).whenComplete((balance, dbEx) -> {
                if (dbEx != null) {
                    reply(sender, () -> msg.get("db-error", papiOf(sender)));
                } else {
                    reply(sender, () -> msg.playerAmount("balance-other", papiOf(sender), nick, balance));
                }
            });
        });
    }

    // /크레딧 지급|차감 <닉네임> <금액>
    private void handleGiveTake(CommandSender sender, String[] args, boolean give) {
        if (args.length < 3) {
            reply(sender, () -> msg.get("usage-admin", papiOf(sender)));
            return;
        }
        String nick = args[1];
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            reply(sender, () -> msg.get("invalid-amount", papiOf(sender)));
            return;
        }
        if (amount <= 0) {
            reply(sender, () -> msg.get("invalid-amount", papiOf(sender)));
            return;
        }

        resolveUuid(nick).whenComplete((opt, ex) -> {
            if (ex != null || opt == null || opt.isEmpty()) {
                reply(sender, () -> msg.get("player-not-found", papiOf(sender)));
                return;
            }
            UUID uuid = opt.get();
            CompletableFuture<Boolean> op = give
                    ? credit.give(uuid, amount, "ADMIN_GIVE")
                    : credit.take(uuid, amount, "ADMIN_TAKE");
            op.whenComplete((ok, dbEx) -> {
                if (dbEx != null) {
                    reply(sender, () -> msg.get("db-error", papiOf(sender)));
                } else if (Boolean.TRUE.equals(ok)) {
                    reply(sender, () -> msg.playerAmount(give ? "give-success" : "take-success", papiOf(sender), nick, amount));
                } else if (!give) {
                    reply(sender, () -> msg.get("take-insufficient", papiOf(sender)));
                } else {
                    reply(sender, () -> msg.get("db-error", papiOf(sender)));
                }
            });
        });
    }

    // /크레딧 인증 <uuid>
    private void handleVerify(CommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, () -> msg.get("usage-admin", papiOf(sender)));
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(args[1].trim());
        } catch (IllegalArgumentException e) {
            reply(sender, () -> msg.get("invalid-uuid", papiOf(sender)));
            return;
        }
        resolveName(uuid).whenComplete((opt, ex) -> {
            if (ex != null || opt == null || opt.isEmpty()) {
                reply(sender, () -> msg.verify("verify-notfound", papiOf(sender), uuid, null));
            } else {
                reply(sender, () -> msg.verify("verify-result", papiOf(sender), uuid, opt.get()));
            }
        });
    }

    // ── 리졸버(메인 스레드에서 Bukkit 조회 → 실패 시 Mojang 비동기) ───────────

    // onCommand 는 메인 스레드에서 호출되므로 여기서 Bukkit 조회는 안전하다.
    private CompletableFuture<Optional<UUID>> resolveUuid(String nick) {
        Player online = Bukkit.getPlayerExact(nick);
        if (online != null) {
            names.cacheName(online.getUniqueId(), online.getName());
            return CompletableFuture.completedFuture(Optional.of(online.getUniqueId()));
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(nick);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached.getUniqueId()));
        }
        return names.resolveUuidOffline(nick); // DB 닉네임 캐시 → Mojang(비동기)
    }

    private CompletableFuture<Optional<String>> resolveName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            names.cacheName(uuid, online.getName());
            return CompletableFuture.completedFuture(Optional.of(online.getName()));
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid); // usercache 조회(웹 요청 없음), 없으면 name=null
        String name = op.getName();
        if (name != null && !name.isBlank()) {
            names.cacheName(uuid, name);
            return CompletableFuture.completedFuture(Optional.of(name));
        }
        return names.resolveNameOffline(uuid); // DB 닉네임 캐시 → Mojang(비동기)
    }

    // ── 탭완성(관리자에게만 서브명령어 노출) ─────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            return List.of(); // 일반 유저에게는 아무 것도 노출하지 않음
        }
        if (args.length == 1) {
            return filterPrefix(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && List.of("확인", "지급", "차감").contains(args[0])) {
            List<String> online = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                online.add(p.getName());
            }
            return filterPrefix(online, args[1]);
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
