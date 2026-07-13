package kr.scfarm.credit.command;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /크레딧} 명령어 처리 + 탭완성.
 *
 * <p>일반 유저는 {@code /크레딧}(본인 잔액)만 사용할 수 있다. 관리자 서브명령어(확인/지급/차감/인증)는
 * {@code credit.admin} 전용이며 권한이 없으면 탭완성/사용법에 노출되지 않는다.
 *
 * <p>Bukkit(online/usercache) 조회는 이 핸들러가 실행되는 <b>메인 스레드</b>에서만 수행하고,
 * DB/HTTP 는 비동기로 넘긴다. 완료 후 메세지 전송은 Adventure 오디언스로 안전하게 처리한다.
 */
public final class CreditCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_ADMIN = "credit.admin";
    private static final String PERM_USE = "credit.use";
    private static final List<String> SUBCOMMANDS = List.of("확인", "지급", "차감", "인증");

    private final CreditService credit;
    private final Messages msg;
    private final NameResolver names;

    public CreditCommand(CreditService credit, Messages msg, NameResolver names) {
        this.credit = credit;
        this.msg = msg;
        this.names = names;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            handleSelf(sender);
            return true;
        }

        // 서브명령어는 전부 관리자 전용
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(msg.get("no-permission"));
            return true;
        }

        String sub = args[0];
        switch (sub) {
            case "확인" -> handleCheck(sender, args);
            case "지급" -> handleGiveTake(sender, args, true);
            case "차감" -> handleGiveTake(sender, args, false);
            case "인증" -> handleVerify(sender, args);
            default -> sender.sendMessage(msg.get("usage-admin"));
        }
        return true;
    }

    // /크레딧 — 본인 잔액
    private void handleSelf(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg.get("player-only"));
            return;
        }
        if (!player.hasPermission(PERM_USE)) {
            player.sendMessage(msg.get("no-permission"));
            return;
        }
        UUID uuid = player.getUniqueId();
        credit.getBalance(uuid).whenComplete((balance, ex) -> {
            if (ex != null) {
                player.sendMessage(msg.get("db-error"));
            } else {
                player.sendMessage(msg.amount("balance-self", balance));
            }
        });
    }

    // /크레딧 확인 <닉네임>
    private void handleCheck(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg.get("usage-admin"));
            return;
        }
        String nick = args[1];
        resolveUuid(nick).whenComplete((opt, ex) -> {
            if (ex != null || opt == null || opt.isEmpty()) {
                sender.sendMessage(msg.get("player-not-found"));
                return;
            }
            UUID uuid = opt.get();
            credit.getBalance(uuid).whenComplete((balance, dbEx) -> {
                if (dbEx != null) {
                    sender.sendMessage(msg.get("db-error"));
                } else {
                    sender.sendMessage(msg.playerAmount("balance-other", nick, balance));
                }
            });
        });
    }

    // /크레딧 지급|차감 <닉네임> <금액>
    private void handleGiveTake(CommandSender sender, String[] args, boolean give) {
        if (args.length < 3) {
            sender.sendMessage(msg.get("usage-admin"));
            return;
        }
        String nick = args[1];
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(msg.get("invalid-amount"));
            return;
        }
        if (amount <= 0) {
            sender.sendMessage(msg.get("invalid-amount"));
            return;
        }

        resolveUuid(nick).whenComplete((opt, ex) -> {
            if (ex != null || opt == null || opt.isEmpty()) {
                sender.sendMessage(msg.get("player-not-found"));
                return;
            }
            UUID uuid = opt.get();
            CompletableFuture<Boolean> op = give
                    ? credit.give(uuid, amount, "ADMIN_GIVE")
                    : credit.take(uuid, amount, "ADMIN_TAKE");
            op.whenComplete((ok, dbEx) -> {
                if (dbEx != null) {
                    sender.sendMessage(msg.get("db-error"));
                } else if (Boolean.TRUE.equals(ok)) {
                    sender.sendMessage(msg.playerAmount(give ? "give-success" : "take-success", nick, amount));
                } else if (!give) {
                    sender.sendMessage(msg.get("take-insufficient"));
                } else {
                    sender.sendMessage(msg.get("db-error"));
                }
            });
        });
    }

    // /크레딧 인증 <uuid>
    private void handleVerify(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg.get("usage-admin"));
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(args[1].trim());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(msg.get("invalid-uuid"));
            return;
        }
        resolveName(uuid).whenComplete((opt, ex) -> {
            if (ex != null || opt == null || opt.isEmpty()) {
                sender.sendMessage(msg.verify("verify-notfound", uuid, null));
            } else {
                sender.sendMessage(msg.verify("verify-result", uuid, opt.get()));
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
