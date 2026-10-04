package kr.suljab;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class SulJabPlugin extends org.bukkit.plugin.java.JavaPlugin implements Listener, CommandExecutor {
    private static final long TOTAL_SECONDS = 100L * 60L;
    private static final long LOCK_SECONDS = 60L;
    private static final double START_BORDER = 600.0;
    private static final double FINAL_BORDER = 150.0;

    private boolean running;
    private World gameWorld;
    private UUID chaser;
    private boolean switchedOnce;
    private long secondsLeft;
    private boolean borderShrunk;

    private BukkitTask timerTask;
    private BossBar bossBar;

    private final Set<UUID> participants = new HashSet<>();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Long> lockUntil = new HashMap<>();
    private final Set<UUID> kitOnRespawn = new HashSet<>();
    private final Map<UUID, Long> trackerUntil = new HashMap<>();

    private double oldBorderSize;
    private double oldBorderX;
    private double oldBorderZ;
    private boolean oldBorderCaptured;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("게임")).setExecutor(this);
    }

    @Override
    public void onDisable() {
        endGame(false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equals("게임")) return false;

        if (args.length == 0) {
            sender.sendMessage("§e/게임 시작 §7| §e/게임 끝 §7| §e/게임 시간");
            return true;
        }

        switch (args[0]) {
            case "시작" -> startGame(sender);
            case "끝" -> {
                if (!running) {
                    sender.sendMessage("§c진행 중인 게임이 없습니다.");
                } else {
                    endGame(true);
                }
            }
            case "시간" -> {
                if (!running) {
                    sender.sendMessage("§c진행 중인 게임이 없습니다.");
                } else {
                    secondsLeft = 16L * 60L;
                    borderShrunk = false;
                    updateBossBar();
                    sender.sendMessage("§a[테스트] 남은 시간이 16:00으로 설정되었습니다.");
                }
            }
            default -> sender.sendMessage("§e/게임 시작 §7| §e/게임 끝 §7| §e/게임 시간");
        }
        return true;
    }

    private void startGame(CommandSender sender) {
        if (running) {
            sender.sendMessage("§c이미 게임이 진행 중입니다.");
            return;
        }

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.size() < 2) {
            sender.sendMessage("§c최소 2명의 플레이어가 필요합니다.");
            return;
        }

        gameWorld = online.get(0).getWorld();
        if (gameWorld.getEnvironment() != World.Environment.NORMAL) {
            gameWorld = Bukkit.getWorlds().stream()
                    .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                    .findFirst().orElse(null);
        }
        if (gameWorld == null) {
            sender.sendMessage("§c오버월드를 찾을 수 없습니다.");
            return;
        }

        running = true;
        switchedOnce = false;
        borderShrunk = false;
        secondsLeft = TOTAL_SECONDS;
        chaser = null;
        participants.clear();
        scores.clear();
        lockUntil.clear();
        kitOnRespawn.clear();
        trackerUntil.clear();

        for (Player p : online) {
            participants.add(p.getUniqueId());
            scores.put(p.getUniqueId(), 0);
            p.teleport(p.getWorld().equals(gameWorld) ? p.getLocation() : gameWorld.getSpawnLocation());
            giveStartKit(p);
            clearGameEffects(p);
        }

        gameWorld.setDifficulty(Difficulty.EASY);
        captureAndSetBorder();
        createBossBar();
        placePlayersAtThreeCorners(online);

        runCountdown();
        sender.sendMessage("§a술래잡기 게임을 시작합니다.");
    }

    private void runCountdown() {
        Bukkit.getOnlinePlayers().forEach(p -> p.sendTitle("§f3", "", 0, 20, 0));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (running) Bukkit.getOnlinePlayers().forEach(p -> p.sendTitle("§f2", "", 0, 20, 0));
        }, 20L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (running) Bukkit.getOnlinePlayers().forEach(p -> p.sendTitle("§f1", "", 0, 20, 0));
        }, 40L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!running) return;
            selectInitialChaser();
            Bukkit.getOnlinePlayers().forEach(p -> p.sendTitle("§d술래가 정해졌습니다!", "", 5, 40, 5));
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (running) startTimer();
            }, 60L);
        }, 60L);
    }

    private void selectInitialChaser() {
        List<Player> available = onlineParticipants();
        if (available.size() < 2) {
            endGame(true);
            return;
        }
        Player selected = available.get(new Random().nextInt(available.size()));
        switchChaser(selected, false);
    }

    private void startTimer() {
        if (timerTask != null) timerTask.cancel();
        updateBossBar();
        timerTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!running) return;
            secondsLeft--;

            if (secondsLeft == 15L * 60L + 30L) {
                Bukkit.broadcastMessage("§e자기장이 30초 후 축소됩니다!");
            }
            if (secondsLeft == 15L * 60L) {
                shrinkBorder();
            }

            updateTrackerParticles();
            updateBossBar();
            if (secondsLeft <= 0) endByScore();
        }, 20L, 20L);
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        long min = Math.max(0, secondsLeft) / 60L;
        long sec = Math.max(0, secondsLeft) % 60L;
        bossBar.setTitle("술래잡기 | 남은 시간 " + String.format(Locale.ROOT, "%02d:%02d", min, sec));
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, secondsLeft / (double) TOTAL_SECONDS)));
    }

    private void createBossBar() {
        if (bossBar != null) bossBar.removeAll();
        bossBar = Bukkit.createBossBar("술래잡기 | 남은 시간 100:00", BarColor.PURPLE, BarStyle.SOLID);
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) bossBar.addPlayer(p);
        }
    }

    private void switchChaser(Player newChaser, boolean afterKill) {
        Player old = getChaser();
        if (old != null && !old.getUniqueId().equals(newChaser.getUniqueId())) {
            clearRoleEffects(old);
            old.sendMessage("당신의 역할은 '러너' 입니다.");
        }

        chaser = newChaser.getUniqueId();
        scores.merge(chaser, -1, Integer::sum);

        clearRoleEffects(newChaser);
        newChaser.addPotionEffect(hiddenEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 0));

        if (afterKill) {
            switchedOnce = true;
            lockUntil.put(chaser, System.currentTimeMillis() + LOCK_SECONDS * 1000L);
            newChaser.addPotionEffect(hiddenEffect(PotionEffectType.BLINDNESS, (int) (LOCK_SECONDS * 20L), 0));
        }

        newChaser.sendMessage("당신의 역할은 '술래' 입니다.");
    }

    private void shrinkBorder() {
        if (borderShrunk || gameWorld == null) return;
        borderShrunk = true;
        WorldBorder border = gameWorld.getWorldBorder();
        border.setSize(FINAL_BORDER);
        Bukkit.broadcastMessage("§c자기장이 축소되었습니다");

        Player c = getChaser();
        if (c != null) c.addPotionEffect(hiddenEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 0));

        double half = FINAL_BORDER / 2.0 - 1.0;
        double cx = border.getCenter().getX();
        double cz = border.getCenter().getZ();
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || p.getWorld() != gameWorld) continue;
            if (!border.isInside(p.getLocation())) {
                double x = p.getX() < cx ? cx - half : cx + half;
                double z = p.getZ() < cz ? cz - half : cz + half;
                Location target = safeCorner(gameWorld, x, z);
                p.teleport(target);
            }
        }
    }

    private Location safeCorner(World world, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int y = world.getHighestBlockYAt(bx, bz) + 1;
        return new Location(world, bx + 0.5, y, bz + 0.5);
    }

    private void placePlayersAtThreeCorners(List<Player> players) {
        WorldBorder border = gameWorld.getWorldBorder();
        double half = START_BORDER / 2.0 - 2.0;
        double cx = border.getCenter().getX();
        double cz = border.getCenter().getZ();
        List<int[]> corners = new ArrayList<>(List.of(new int[]{-1,-1}, new int[]{-1,1}, new int[]{1,-1}, new int[]{1,1}));
        Collections.shuffle(corners);
        List<int[]> selected = corners.subList(0, 3);
        Collections.shuffle(players);
        for (int i = 0; i < players.size(); i++) {
            int[] corner = selected.get(i % 3);
            players.get(i).teleport(safeCorner(gameWorld, cx + corner[0] * half, cz + corner[1] * half));
        }
    }

    private void captureAndSetBorder() {
        WorldBorder border = gameWorld.getWorldBorder();
        oldBorderSize = border.getSize();
        oldBorderX = border.getCenter().getX();
        oldBorderZ = border.getCenter().getZ();
        oldBorderCaptured = true;
        Location spawn = gameWorld.getSpawnLocation();
        border.setCenter(spawn.getX(), spawn.getZ());
        border.setSize(START_BORDER);
    }

    private void giveStartKit(Player p) {
        p.setLevel(10000);
        p.getInventory().addItem(new ItemStack(Material.ENCHANTING_TABLE, 1));
        p.getInventory().addItem(new ItemStack(Material.BOOKSHELF, 64));
    }

    private PotionEffect hiddenEffect(PotionEffectType type, int duration, int amplifier) {
        return new PotionEffect(type, duration, amplifier, true, false, true);
    }

    private void clearRoleEffects(Player p) {
        p.removePotionEffect(PotionEffectType.RESISTANCE);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.removePotionEffect(PotionEffectType.STRENGTH);
        lockUntil.remove(p.getUniqueId());
    }

    private void clearGameEffects(Player p) {
        clearRoleEffects(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!running || !participants.contains(event.getEntity().getUniqueId())) return;

        Player victim = event.getEntity();
        boolean victimChaser = victim.getUniqueId().equals(chaser);
        Player killer = victim.getKiller();
        boolean killedByChaser = killer != null && killer.getUniqueId().equals(chaser) && !victimChaser;

        if (victimChaser) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            return;
        }

        if (killedByChaser) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            kitOnRespawn.add(victim.getUniqueId());

            Player oldChaser = killer;
            clearRoleEffects(oldChaser);
            oldChaser.sendMessage("당신의 역할은 '러너' 입니다.");
            chaser = victim.getUniqueId();
            scores.merge(chaser, -1, Integer::sum);
            switchedOnce = true;
            return;
        }

        EntityDamageEvent last = victim.getLastDamageCause();
        boolean natural = last != null && (last.getCause() == EntityDamageEvent.DamageCause.FALL
                || last.getCause() == EntityDamageEvent.DamageCause.DROWNING
                || last.getCause() == EntityDamageEvent.DamageCause.FIRE
                || last.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                || last.getCause() == EntityDamageEvent.DamageCause.LAVA
                || last.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION
                || last.getCause() == EntityDamageEvent.DamageCause.STARVATION
                || last.getCause() == EntityDamageEvent.DamageCause.VOID
                || last.getCause() == EntityDamageEvent.DamageCause.FREEZE
                || last.getCause() == EntityDamageEvent.DamageCause.HOT_FLOOR);
        if (natural) kitOnRespawn.add(victim.getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!running || !participants.contains(event.getPlayer().getUniqueId())) return;
        UUID id = event.getPlayer().getUniqueId();
        if (kitOnRespawn.remove(id)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (running) giveStartKit(event.getPlayer());
            }, 1L);
        }
        if (id.equals(chaser)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!running) return;
                Player p = event.getPlayer();
                p.addPotionEffect(hiddenEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 0));
                if (switchedOnce) {
                    lockUntil.put(id, System.currentTimeMillis() + LOCK_SECONDS * 1000L);
                    p.addPotionEffect(hiddenEffect(PotionEffectType.BLINDNESS, (int) (LOCK_SECONDS * 20L), 0));
                }
                if (borderShrunk) p.addPotionEffect(hiddenEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 0));
                p.sendMessage("당신의 역할은 '술래' 입니다.");
            }, 1L);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!running) return;
        Long until = lockUntil.get(event.getPlayer().getUniqueId());
        if (until == null || System.currentTimeMillis() >= until) {
            lockUntil.remove(event.getPlayer().getUniqueId());
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!running || event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        if (!p.getUniqueId().equals(chaser)) return;
        if (event.getItem() == null || event.getItem().getType() != Material.DIAMOND) return;

        Player target = nearestRunner(p);
        if (target == null) return;
        trackerUntil.put(p.getUniqueId(), System.currentTimeMillis() + 5000L);
    }

    private Player nearestRunner(Player source) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (UUID id : participants) {
            if (id.equals(chaser)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || p.getWorld() != source.getWorld()) continue;
            double d = source.getLocation().distanceSquared(p.getLocation());
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        return nearest;
    }

    private void updateTrackerParticles() {
        if (trackerUntil.isEmpty()) return;
        Iterator<Map.Entry<UUID, Long>> it = trackerUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null || !p.isOnline() || System.currentTimeMillis() > entry.getValue()) {
                it.remove();
                continue;
            }
            Player target = nearestRunner(p);
            if (target == null) continue;
            org.bukkit.util.Vector direction = target.getLocation().toVector().subtract(p.getLocation().toVector()).normalize();
            for (int i = 1; i <= 8; i++) {
                Location point = p.getEyeLocation().clone().add(direction.clone().multiply(i * 1.2));
                p.spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!running || !participants.contains(event.getPlayer().getUniqueId())) return;
        Material type = event.getBlock().getType();
        if (!isDoubleOre(type)) return;

        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        boolean silk = tool.containsEnchantment(Enchantment.SILK_TOUCH);
        Collection<ItemStack> drops = event.getBlock().getDrops(tool, event.getPlayer());
        event.setDropItems(false);

        for (ItemStack drop : drops) {
            Material out = autoSmelt(type, silk) != null ? autoSmelt(type, silk) : drop.getType();
            int amount = drop.getAmount() * 2;
            ItemStack result = new ItemStack(out, amount);
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), result);
        }
    }

    private boolean isDoubleOre(Material m) {
        return switch (m) {
            case COAL_ORE, DEEPSLATE_COAL_ORE,
                 IRON_ORE, DEEPSLATE_IRON_ORE,
                 COPPER_ORE, DEEPSLATE_COPPER_ORE,
                 GOLD_ORE, DEEPSLATE_GOLD_ORE,
                 REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE,
                 LAPIS_ORE, DEEPSLATE_LAPIS_ORE,
                 DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE,
                 EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> true;
            default -> false;
        };
    }

    private Material autoSmelt(Material ore, boolean silk) {
        if (silk) return null;
        return switch (ore) {
            case IRON_ORE, DEEPSLATE_IRON_ORE -> Material.IRON_INGOT;
            case COPPER_ORE, DEEPSLATE_COPPER_ORE -> Material.COPPER_INGOT;
            case GOLD_ORE, DEEPSLATE_GOLD_ORE -> Material.GOLD_INGOT;
            default -> null;
        };
    }

    @EventHandler
    public void onPortal(PlayerPortalEvent event) {
        if (!running || !participants.contains(event.getPlayer().getUniqueId())) return;
        if (event.getTo() != null && event.getTo().getWorld() != null && event.getTo().getWorld().getEnvironment() != World.Environment.NORMAL) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c게임 중에는 네더와 엔드에 들어갈 수 없습니다.");
        }
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        if (!running || !participants.contains(event.getPlayer().getUniqueId())) return;
        if (event.getPlayer().getWorld().getEnvironment() != World.Environment.NORMAL) {
            event.getPlayer().teleport(gameWorld.getSpawnLocation());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!running || !participants.contains(event.getPlayer().getUniqueId())) return;
        trackerUntil.remove(event.getPlayer().getUniqueId());
    }

    private void endByScore() {
        int lowest = Integer.MAX_VALUE;
        List<String> losers = new ArrayList<>();
        for (UUID id : participants) {
            int score = scores.getOrDefault(id, 0);
            if (score < lowest) {
                lowest = score;
                losers.clear();
                losers.add(nameOf(id));
            } else if (score == lowest) {
                losers.add(nameOf(id));
            }
        }
        Bukkit.broadcastMessage("§d술래잡기 종료!");
        Bukkit.broadcastMessage("§c패배: " + String.join(", ", losers) + " §7(점수 " + lowest + ")");
        endGame(false);
    }

    private void endGame(boolean announce) {
        if (!running) return;
        running = false;
        if (timerTask != null) timerTask.cancel();
        timerTask = null;

        if (announce) Bukkit.broadcastMessage("§d술래잡기 게임이 종료되었습니다.");

        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                clearGameEffects(p);
            }
        }
        if (bossBar != null) bossBar.removeAll();
        bossBar = null;
        restoreBorder();

        chaser = null;
        participants.clear();
        scores.clear();
        lockUntil.clear();
        kitOnRespawn.clear();
        trackerUntil.clear();
        oldBorderCaptured = false;
    }

    private void restoreBorder() {
        if (!oldBorderCaptured || gameWorld == null) return;
        WorldBorder border = gameWorld.getWorldBorder();
        border.setCenter(oldBorderX, oldBorderZ);
        border.setSize(oldBorderSize);
    }

    private Player getChaser() {
        return chaser == null ? null : Bukkit.getPlayer(chaser);
    }

    private List<Player> onlineParticipants() {
        List<Player> list = new ArrayList<>();
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) list.add(p);
        }
        return list;
    }

    private String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : id.toString().substring(0, 8);
    }
}
