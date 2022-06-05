package com.cavetale.hideandseek;

import com.cavetale.core.command.CommandArgCompleter;
import com.cavetale.core.font.VanillaItems;
import com.cavetale.fam.trophy.Highscore;
import com.cavetale.mytems.item.trophy.TrophyCategory;
import com.cavetale.sidebar.PlayerSidebarEvent;
import com.cavetale.sidebar.Priority;
import com.destroystokyo.paper.MaterialTags;
import com.winthier.playercache.PlayerCache;
import com.winthier.title.TitlePlugin;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.*;

public final class HideAndSeekPlugin extends JavaPlugin implements Listener {
    Phase phase = Phase.IDLE;
    int ticks = 0;
    int phaseTicks = 0;
    int bonusTicks = 0;
    Set<UUID> hiders = new HashSet<>();
    Set<UUID> seekers = new HashSet<>();
    Random random = new Random();
    Tag tag;
    File tagFile;
    Map<UUID, Enum> disguises = new HashMap<>();
    Map<UUID, Long> itemCooldown = new HashMap<>();
    Map<UUID, Component> hiderPrefixMap = new HashMap<>();
    Set<Entity> distractions = new HashSet<>();
    private boolean teleporting;
    private List<Highscore> highscore = List.of();
    public static final Component TITLE = Component.join(JoinConfiguration.noSeparators(),
                                                         VanillaItems.SPYGLASS.component,
                                                         Component.text("Hide and Seek", NamedTextColor.LIGHT_PURPLE));

    static final class Tag {
        String worldName;
        Map<UUID, Integer> fairness = new HashMap<>();
        Map<UUID, Integer> scores = new HashMap<>();
        int gameTime = 60 * 5;
        int hideTime = 30;
        int glowTime = 30;
        int bonusTime = 60;
        boolean event;
    }

    enum Phase {
        IDLE,
        HIDE,
        SEEK,
        END;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::onTick, 1L, 1L);
        tagFile = new File(getDataFolder(), "save.json");
        loadTag();
    }

    @Override
    public void onDisable() {
        saveTag();
        for (Player player : getServer().getOnlinePlayers()) {
            undisguise(player);
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender,
                             final Command command,
                             final String alias,
                             final String[] args) {
        if (args.length == 0) return false;
        return onCommand(sender, args[0], Arrays.copyOfRange(args, 1, args.length));
    }

    void loadTag() {
        tag = Json.load(tagFile, Tag.class, Tag::new);
        computeHighscore();
    }

    void saveTag() {
        Json.save(tagFile, tag, true);
    }

    boolean onCommand(CommandSender sender, String cmd, String[] args) {
        Player player = sender instanceof Player ? (Player) sender : null;
        switch (cmd) {
        case "setworld": {
            tag.worldName = player.getWorld().getName();
            saveTag();
            player.sendMessage("World is now " + tag.worldName);
            return true;
        }
        case "start": {
            if (startGame()) {
                sender.sendMessage("Game started");
            } else {
                sender.sendMessage("Error! See console.");
            }
            return true;
        }
        case "stop": {
            stopGame();
            sender.sendMessage("Game stopped");
            return true;
        }
        case "fair": {
            List<Player> players = new ArrayList<>(getServer().getOnlinePlayers());
            sender.sendMessage("" + players.size() + " players");
            for (Player online : players) {
                sender.sendMessage(" " + online.getName() + ": " + getFairness(online));
            }
            return true;
        }
        case "save": {
            saveTag();
            sender.sendMessage("Tag saved");
            return true;
        }
        case "load": {
            loadTag();
            sender.sendMessage("Tag (re)loaded");
            return true;
        }
        case "testdisguise": {
            disguise(player);
            sender.sendMessage("Player disguised");
            return true;
        }
        case "gametime": {
            if (args.length == 1) {
                tag.gameTime = Integer.parseInt(args[0]);
                saveTag();
            }
            sender.sendMessage("Game time = " + tag.gameTime);
            return true;
        }
        case "hidetime": {
            if (args.length == 1) {
                tag.hideTime = Integer.parseInt(args[0]);
                saveTag();
            }
            sender.sendMessage("Hide time = " + tag.hideTime);
            return true;
        }
        case "glowtime": {
            if (args.length == 1) {
                tag.glowTime = Integer.parseInt(args[0]);
                saveTag();
            }
            sender.sendMessage("Glow time = " + tag.glowTime);
            return true;
        }
        case "bonustime": {
            if (args.length == 1) {
                tag.bonusTime = Integer.parseInt(args[0]);
                saveTag();
            }
            sender.sendMessage("Bonus time = " + tag.bonusTime);
            return true;
        }
        case "event": {
            if (args.length > 1) return false;
            if (args.length >= 1) {
                try {
                    tag.event = Boolean.parseBoolean(args[0]);
                } catch (IllegalArgumentException iae) {
                    sender.sendMessage("Boolean expected: " + args[0]);
                    return true;
                }
                saveTag();
            }
            sender.sendMessage("Event mode = " + tag.event);
            return true;
        }
        case "reward":
            int res = rewardHighscore();
            sender.sendMessage(text(res + " players rewarded", AQUA));
            return true;
        case "score":
            if (args.length == 1 && args[0].equals("reset")) {
                tag.scores.clear();
                computeHighscore();
                sender.sendMessage(text("Scores reset", AQUA));
                return true;
            }
            if (args.length == 3 && args[0].equals("add")) {
                PlayerCache target = PlayerCache.require(args[1]);
                int value = CommandArgCompleter.requireInt(args[2], i -> i != 0);
                addScore(target.uuid, value);
                computeHighscore();
                sender.sendMessage(text("Score of " + target.name + " adjusted by " + value, AQUA));
                return true;
            }
            return false;
        default:
            sender.sendMessage("Unknown subcommand: " + cmd);
            return true;
        }
    }

    protected void stopGame() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getInventory().clear();
        }
        disguises.clear();
        for (Player hider : getHiders()) {
            undisguise(hider);
        }
        hiders.clear();
        seekers.clear();
        setPhase(Phase.IDLE);
        for (Entity entity : distractions) {
            entity.remove();
        }
        distractions.clear();
    }

    protected boolean startGame() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getInventory().clear();
        }
        List<Player> players = Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.getGameMode() != GameMode.SPECTATOR)
            .collect(Collectors.toList());
        Collections.shuffle(players);
        Collections.sort(players, (a, b) -> Integer.compare(getFairness(b), getFairness(a)));
        int half = players.size() <= 2 ? 1 : players.size() / 2 + 1;
        hiders.clear();
        hiderPrefixMap.clear();
        seekers.clear();
        for (int i = 0; i < players.size(); i += 1) {
            Player player = players.get(i);
            undisguise(player);
            if (i < half) {
                hiders.add(player.getUniqueId());
            } else {
                seekers.add(player.getUniqueId());
            }
            player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.MASTER, 0.125f, 2.0f);
            player.getInventory().clear();
        }
        for (Player hider : getHiders()) {
            addFairness(hider, -2);
            disguise(hider);
            hider.teleport(getHideLocation());
            hider.showTitle(Title.title(Component.text("Hide!", NamedTextColor.GREEN),
                                        Component.text("You're a Hider", NamedTextColor.GREEN)));
            if (disguises.get(hider.getUniqueId()) instanceof EntityType) {
                hider.getInventory().addItem(summonWheat(1));
            }
            if (random.nextBoolean()) {
                hider.getInventory().addItem(copySlime(1));
            } else {
                hider.getInventory().addItem(rerollFoot(3));
                hider.getInventory().addItem(makeInvisItem(1));
            }
        }
        for (Player seeker : getSeekers()) {
            addFairness(seeker, 1);
            seeker.teleport(getSeekLocation());
            seeker.showTitle(Title.title(Component.text("Wait!", NamedTextColor.RED),
                                         Component.text("You're a Seeker", NamedTextColor.RED)));
            giveSeekerItems(seeker);
        }
        setPhase(Phase.HIDE);
        return true;
    }

    protected void giveSeekerItems(Player seeker) {
        seeker.getInventory().addItem(makeCompass(1));
        seeker.getInventory().addItem(hintEye(3));
        seeker.getInventory().addItem(new ItemStack(Material.ENDER_PEARL, 3));
        seeker.getInventory().addItem(new ItemStack(Material.SPYGLASS));
    }

    void disguise(Player player) {
        List<EntityType> animals = Arrays
            .asList(EntityType.COW, EntityType.CHICKEN,
                    EntityType.SHEEP, EntityType.PIG, EntityType.BAT,
                    EntityType.GLOW_SQUID, EntityType.BEE,
                    EntityType.CAT, EntityType.WOLF,
                    EntityType.SNOWMAN, EntityType.RABBIT,
                    EntityType.AXOLOTL, EntityType.GOAT);
        List<Material> blocks = Arrays
            .asList(Material.GRASS_BLOCK,
                    Material.DIRT,
                    Material.BRICKS,
                    Material.STONE,
                    Material.STONE_BRICKS,
                    Material.GOLD_BLOCK,
                    Material.IRON_BLOCK,
                    Material.DIAMOND_BLOCK,
                    Material.HAY_BLOCK,
                    Material.GLOWSTONE,
                    Material.OAK_LOG,
                    Material.BIRCH_LOG,
                    Material.OAK_LEAVES,
                    Material.SPRUCE_LEAVES,
                    Material.DARK_OAK_LEAVES,
                    Material.COBBLESTONE,
                    Material.GOLD_ORE,
                    Material.DIAMOND_ORE,
                    Material.IRON_ORE,
                    Material.SAND,
                    Material.GRAVEL,
                    Material.SNOW_BLOCK,
                    Material.FARMLAND,
                    Material.SPRUCE_LOG,
                    Material.COARSE_DIRT,
                    Material.BOOKSHELF
                    );
        Enum enume;
        if (random.nextBoolean()) {
            enume = blocks.get(random.nextInt(blocks.size()));
        } else {
            enume = animals.get(random.nextInt(animals.size()));
        }
        disguise(player, enume);
    }

    void disguise(Player player, Enum enume) {
        player.setMetadata("nostream", new FixedMetadataValue(this, true));
        if (enume instanceof EntityType) {
            disguises.put(player.getUniqueId(), enume);
            EntityType type = (EntityType) enume;
            consoleCommand("disguiseplayer " + player.getName() + " " + type.name().toLowerCase());
            Component prefix = Component.text("[" + entityName(type) + "]", NamedTextColor.GREEN);
            hiderPrefixMap.put(player.getUniqueId(), prefix);
            TitlePlugin.getInstance().setPlayerListPrefix(player, prefix);
        } else if (enume instanceof Material) {
            disguises.put(player.getUniqueId(), enume);
            Material material = (Material) enume;
            consoleCommand("disguiseplayer " + player.getName() + " falling_block " + material.name().toLowerCase());
            Component prefix = Component.text("[" + blockName(material) + "]", NamedTextColor.GREEN);
            hiderPrefixMap.put(player.getUniqueId(), prefix);
            TitlePlugin.getInstance().setPlayerListPrefix(player, prefix);
        }
    }

    void redisguise(Player player) {
        Enum enume = disguises.get(player.getUniqueId());
        if (enume == null) {
            disguise(player);
        } else {
            disguise(player, enume);
        }
    }

    public static String toCamelCase(String in) {
        return in.substring(0, 1).toUpperCase()
            + in.substring(1).toLowerCase();
    }

    public static String toCamelCase(String[] in) {
        String[] out = new String[in.length];
        for (int i = 0; i < in.length; i += 1) {
            out[i] = toCamelCase(in[i]);
        }
        return String.join(" ", out);
    }

    public static String toCamelCase(Enum en) {
        return toCamelCase(en.name().split("_"));
    }

    String blockName(Material material) {
        return material.isItem()
            ? new ItemStack(material).getI18NDisplayName()
            : toCamelCase(material);
    }

    String entityName(EntityType type) {
        return toCamelCase(type);
    }

    void undisguise(Player player) {
        player.removeMetadata("nostream", this);
        consoleCommand("undisguiseplayer " + player.getName());
        TitlePlugin.getInstance().setPlayerListPrefix(player, (Component) null);
    }

    List<Player> getHiders() {
        return hiders.stream()
            .map(Bukkit::getPlayer)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    List<Player> getSeekers() {
        return seekers.stream()
            .map(Bukkit::getPlayer)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    void setPhase(Phase newPhase) {
        phase = newPhase;
        phaseTicks = 0;
        bonusTicks = 0;
        switch (newPhase) {
        case SEEK:
            for (Player player : getServer().getOnlinePlayers()) {
                player.showTitle(Title.title(Component.text("Seek!", NamedTextColor.GREEN),
                                             Component.empty()));
                player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.MASTER, 0.125f, 2.0f);
                // Find hiders that didn't move
                if (hiders.contains(player.getUniqueId())) {
                    Location location = player.getLocation();
                    Location location2 = getHideLocation();
                    if (!location.getWorld().equals(location2.getWorld())
                        || location.distanceSquared(location2) < 9.0) {
                        hiders.remove(player.getUniqueId());
                        undisguise(player);
                        hiders.remove(player.getUniqueId());
                        seekers.add(player.getUniqueId());
                        giveSeekerItems(player);
                        tag.fairness.remove(player.getUniqueId());
                    }
                }
            }
            break;
        case END:
            if (hiders.isEmpty()) {
                for (Player player : getServer().getOnlinePlayers()) {
                    player.showTitle(Title.title(Component.text("Seekers win!", NamedTextColor.GREEN),
                                                 Component.empty()));
                    player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.125f, 2.0f);
                }
            } else {
                for (Player hider : getHiders()) {
                    addFairness(hider, 1);
                    if (tag.event) {
                        addScore(hider.getUniqueId(), 5);
                    }
                    if (tag.event) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "titles unlockset " + hider.getName() + " Hider Sneaky");
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ml add " + hider.getName());
                    }
                }
                if (tag.event) computeHighscore();
                for (Player player : getServer().getOnlinePlayers()) {
                    player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.125f, 2.0f);
                    player.showTitle(Title.title(Component.text("Hiders win!", NamedTextColor.GREEN),
                                                 Component.empty()));
                }
            }
            saveTag();
            break;
        default:
            break;
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isGameWorld(event.getPlayer().getWorld())) return;
        if (event.getPlayer().isOp()) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isGameWorld(event.getPlayer().getWorld())) return;
        if (event.getPlayer().isOp()) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (phase != Phase.IDLE) {
            seekers.remove(event.getPlayer().getUniqueId());
            hiders.remove(event.getPlayer().getUniqueId());
        }
        undisguise(event.getPlayer());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (phase != Phase.IDLE) {
            Player player = event.getPlayer();
            seekers.add(player.getUniqueId());
            giveSeekerItems(player);
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (teleporting) return;
        if (!isGameWorld(event.getPlayer().getWorld())) return;
        if (phase == Phase.IDLE) return;
        if (event.getPlayer().isOp()) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        Player player = event.getPlayer();
        if (isSeeker(player)) return;
        event.setCancelled(true);
        Component message = Component.text("You're not a seeker!", NamedTextColor.RED);
        player.sendMessage(message);
        player.sendActionBar(message);
    }

    boolean consoleCommand(String cmd) {
        getLogger().info("Console command: " + cmd);
        return getServer().dispatchCommand(getServer().getConsoleSender(), cmd);
    }

    void onTick() {
        for (Player player : getServer().getOnlinePlayers()) {
            player.setHealth(20.0);
            player.setFoodLevel(20);
            player.setSaturation(20.0f);
            player.setRemainingAir(player.getMaximumAir());
            player.setFireTicks(0);
        }
        switch (phase) {
        case HIDE: {
            if (getTimeLeft() <= 0) {
                setPhase(Phase.SEEK);
                return;
            }
            Location anchor = getSeekLocation();
            for (Player seeker : getSeekers()) {
                Location ploc = seeker.getLocation();
                if (!ploc.getWorld().equals(anchor.getWorld()) || ploc.distance(anchor) > 8.0) {
                    Location to = anchor.clone();
                    to.setPitch(ploc.getPitch());
                    to.setYaw(ploc.getYaw());
                    seeker.teleport(to);
                    Component message = Component.text("You can start seeking in " + getTimeLeft() + " seconds!",
                                                       NamedTextColor.RED);
                    seeker.sendMessage(message);
                    seeker.sendActionBar(message);
                }
            }
            break;
        }
        case SEEK:
            if (bonusTicks > 0) {
                bonusTicks -= 1;
            }
            if (getTimeLeft() <= 0) {
                for (Player hider : getHiders()) {
                    undisguise(hider);
                }
                setPhase(Phase.END);
                return;
            }
            if (hiders.isEmpty()) {
                for (Player hider : getHiders()) {
                    undisguise(hider);
                }
                setPhase(Phase.END);
                return;
            }
            for (Player hider : getHiders()) {
                if (ticks % 20 == 0) {
                    if (getTimeLeft() < tag.glowTime) {
                        hider.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 1, true, false));
                    }
                    hider.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 60, 3, true, false));
                }
            }
            for (Player seeker : getSeekers()) {
                seeker.sendActionBar(getHint(seeker));
                updateCompassTarget(seeker);
            }
            break;
        case END:
            if (getTimeLeft() <= 0) {
                stopGame();
                startGame();
                return;
            }
            break;
        default: return;
        }
        ticks += 1;
        phaseTicks += 1;
    }

    void hint() {
        for (Player hider : getHiders()) {
            hider.getWorld().playSound(hider.getLocation(), Sound.ENTITY_CAT_AMBIENT, SoundCategory.MASTER, 1.0f, 2.0f);
        }
    }

    int getTimeLeft() {
        switch (phase) {
        case HIDE: return tag.hideTime - phaseTicks / 20;
        case SEEK: return Math.max(tag.gameTime - phaseTicks / 20, bonusTicks / 20);
        case END: return 30 - phaseTicks / 20;
        default: return 0;
        }
    }

    protected Component getHint(Player player) {
        Location loc = player.getLocation();
        double min = Double.MAX_VALUE;
        for (Player hider : getHiders()) {
            if (!hider.getWorld().equals(player.getWorld())) continue;
            min = Math.min(min, hider.getLocation().distanceSquared(loc));
        }
        if (min < 12 * 12) {
            return Component.text("HOT", (ticks % 4 == 2 ? NamedTextColor.GOLD : NamedTextColor.AQUA), TextDecoration.BOLD);
        } else if (min < 24 * 24) {
            return Component.text("Warmer", NamedTextColor.GOLD);
        } else if (min < 48 * 48) {
            return Component.text("Warm", NamedTextColor.YELLOW);
        } else {
            return Component.text("Cold", NamedTextColor.AQUA);
        }
    }

    protected void updateCompassTarget(Player seeker) {
        double min = Double.MAX_VALUE;
        Location loc = seeker.getLocation();
        Location target = null;
        for (Player hider : getHiders()) {
            if (!hider.getWorld().equals(seeker.getWorld())) continue;
            Location hiderLoc = hider.getLocation();
            double dist = hiderLoc.distanceSquared(loc);
            if (dist < min) {
                min = dist;
                target = hiderLoc;
            }
        }
        final int minDistance = 32;
        if (target == null || min < (minDistance * minDistance)) {
            // Spin in circles
            double fraction = ((double) (ticks % 50)) / 50.0;
            double dx = 32.0 * Math.cos(fraction * Math.PI * 2.0);
            double dz = 32.0 * Math.sin(fraction * Math.PI * 2.0);
            seeker.setCompassTarget(loc.add(dx, 0.0, dz));
            return;
        }
        Location targetLoc = new Location(seeker.getWorld(),
                                          (double) (((target.getBlockX() / minDistance) * minDistance) + (minDistance / 2)),
                                          loc.getBlockY(),
                                          (double) (((target.getBlockZ() / minDistance) * minDistance) + (minDistance / 2)));
        seeker.setCompassTarget(targetLoc);
    }

    @EventHandler
    void onPlayerSidebar(PlayerSidebarEvent event) {
        List<Component> lines = new ArrayList<>();
        lines.add(TITLE);
        Player player = event.getPlayer();
        Component identity = Component.empty();
        if (seekers.contains(player.getUniqueId())) {
            identity = Component.text("You're a Seeker!", NamedTextColor.GOLD);
        } else if (hiders.contains(player.getUniqueId())) {
            identity = Component.text("You're a Hider!", NamedTextColor.LIGHT_PURPLE);
        } else {
            identity = Component.text("You were found!", NamedTextColor.GRAY);
        }
        switch (phase) {
        case IDLE: break;
        case HIDE: {
            int timeLeft = getTimeLeft();
            int minutes = timeLeft / 60;
            int seconds = timeLeft % 60;
            lines.add(identity);
            lines.add(Component.text("Hiding ", NamedTextColor.LIGHT_PURPLE)
                      .append(Component.text(String.format("%02d:%02d", minutes, seconds), NamedTextColor.WHITE)));
            break;
        }
        case SEEK: {
            int timeLeft = getTimeLeft();
            int minutes = timeLeft / 60;
            int seconds = timeLeft % 60;
            lines.add(identity);
            if (seekers.contains(player.getUniqueId()) || player.getGameMode() == GameMode.SPECTATOR) {
                lines.add(Component.text("Hint: ", NamedTextColor.GRAY)
                          .append(getHint(player)));
            }
            lines.add(Component.text("Seeking ", NamedTextColor.GRAY)
                      .append(Component.text(String.format("%02d:%02d", minutes, seconds), NamedTextColor.WHITE)));
            lines.add(Component.text("Seekers: ", NamedTextColor.GRAY)
                      .append(Component.text(seekers.size(), NamedTextColor.WHITE)));
            lines.add(Component.text("Hiders: ", NamedTextColor.GRAY)
                      .append(Component.text(hiders.size(), NamedTextColor.WHITE)));
            List<Player> hiderList = getHiders();
            Collections.sort(hiderList, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (Player hider : hiderList) {
                lines.add(hider.displayName());
            }
            break;
        }
        case END:
            if (hiders.isEmpty()) {
                lines.add(Component.text("Seekers Win!!!", NamedTextColor.GOLD));
            } else {
                lines.add(Component.text("Hiders Win!!!", NamedTextColor.LIGHT_PURPLE));
            }
            break;
        default:
            break;
        }
        if (tag.event) {
            lines.addAll(Highscore.sidebar(highscore));
        }
        if (lines.isEmpty()) return;
        event.add(this, Priority.HIGHEST, lines);
    }

    @EventHandler
    void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        event.setCancelled(true);
        if (phase != Phase.SEEK) return;
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof Player)) return;
        Player seeker = (Player) event.getDamager();
        Player hider = (Player) event.getEntity();
        discover(seeker, hider);
    }

    @EventHandler
    void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        event.setCancelled(true);
        if (phase != Phase.SEEK) return;
        if (!(event.getRightClicked() instanceof Player)) return;
        Player seeker = event.getPlayer();
        if (!seekers.contains(seeker.getUniqueId())) return;
        Player hider = (Player) event.getRightClicked();
        if (!hiders.contains(hider.getUniqueId())) {
            Entity target = seeker.getTargetEntity(4);
            if (target instanceof Player) hider = (Player) target;
        }
        discover(seeker, hider);
    }

    @EventHandler
    void onPlayerInteractBlock(PlayerInteractEvent event) {
        switch (event.getAction()) {
        case LEFT_CLICK_AIR:
        case LEFT_CLICK_BLOCK:
        case RIGHT_CLICK_AIR:
        case RIGHT_CLICK_BLOCK:
            break;
        default:
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (phase != Phase.SEEK) return;
        Player seeker = event.getPlayer();
        if (!seekers.contains(seeker.getUniqueId())) return;
        if (!seeker.getWorld().getName().equals(tag.worldName)) return;
        Entity target = seeker.getTargetEntity(4);
        if (target instanceof Player) {
            discover(seeker, (Player) target);
            return;
        }
        Location loc = seeker.getEyeLocation();
        Vector vec = loc.getDirection().normalize().multiply(0.25);
        for (int i = 0; i < 8; i += 1) {
            loc = loc.add(vec);
            for (Entity entity : loc.getWorld().getNearbyEntities(loc, 0.25, 0.25, 0.25)) {
                if (!(entity instanceof Player)) continue;
                if (discover(seeker, (Player) entity)) return;
            }
        }
    }

    boolean discover(Player seeker, Player hider) {
        if (!hiders.contains(hider.getUniqueId())) return false;
        if (!seekers.contains(seeker.getUniqueId())) {
            Component message = Component.text("You're not a seeker!", NamedTextColor.RED);
            seeker.sendMessage(message);
            seeker.sendActionBar(message);
            return false;
        }
        undisguise(hider);
        hiders.remove(hider.getUniqueId());
        seekers.add(hider.getUniqueId());
        giveSeekerItems(hider);
        addFairness(seeker, 1);
        if (tag.event) {
            addScore(seeker.getUniqueId(), 1);
            computeHighscore();
        }
        bonusTicks = Math.max(bonusTicks, tag.bonusTime * 20);
        Component message = Component.text(seeker.getName() + " discovered " + hider.getName() + "!",
                                           NamedTextColor.GREEN);
        for (Player target : getServer().getOnlinePlayers()) {
            target.sendMessage(message);
            target.showTitle(Title.title(Component.empty(),
                                         message));
            target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.2f, 2.0f);
        }
        seeker.getInventory().addItem(hintEye(3));
        seeker.getInventory().addItem(new ItemStack(Material.ENDER_PEARL, 3));
        if (tag.event) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ml add " + seeker.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "titles unlockset " + seeker.getName() + " Seeker Detective");
        }
        return true;
    }

    @EventHandler
    void onArmor(PlayerArmorStandManipulateEvent event) {
        if (event.getPlayer().isOp()) return;
        event.setCancelled(true);
    }

    public boolean isGameWorld(World world) {
        return world.getName().equals(tag.worldName);
    }

    public int getFairness(Player player) {
        Integer val = tag.fairness.get(player.getUniqueId());
        return val != null ? val : 0;
    }

    public int addFairness(Player player, int amount) {
        UUID uuid = player.getUniqueId();
        int val = tag.fairness.getOrDefault(uuid, 0);
        int newVal = Math.max(0, val + amount);
        tag.fairness.put(uuid, newVal);
        return val + newVal;
    }

    public int getScore(UUID uuid) {
        return tag.scores.getOrDefault(uuid, 0);
    }

    public void addScore(UUID uuid, int value) {
        tag.scores.put(uuid, Math.max(0, getScore(uuid) + value));
    }

    public World getWorld() {
        return Bukkit.getWorld(tag.worldName);
    }

    public Location getHideLocation() {
        return getWorld().getSpawnLocation();
    }

    public Location getSeekLocation() {
        return getWorld().getSpawnLocation();
    }

    public boolean isSeeker(Player player) {
        return seekers.contains(player.getUniqueId());
    }

    @EventHandler
    void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof BlockInventoryHolder || event.getInventory().getHolder() instanceof Entity) {
            if (!isGameWorld(event.getPlayer().getWorld())) return;
            if (event.getPlayer().isOp()) return;
            event.setCancelled(true);
        }
    }

    @EventHandler
    void onPlayerInteractItem(PlayerInteractEvent event) {
        if (!isGameWorld(event.getPlayer().getWorld())) return;
        if (event.hasBlock()) {
            switch (event.getClickedBlock().getType()) {
            case FARMLAND:
            case FLOWER_POT:
                event.setCancelled(true);
            default: break;
            }
        }
        switch (event.getAction()) {
        case RIGHT_CLICK_BLOCK:
        case RIGHT_CLICK_AIR:
            break;
        default:
            return;
        }
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equals(tag.worldName)) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.hasItem()) return;
        ItemStack item = event.getItem();
        Block block = event.hasBlock() ? event.getClickedBlock() : null;
        if (item == null) return;
        Long cooldown = itemCooldown.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        long then = now + 1000;
        if (cooldown != null && cooldown > now) return;
        switch (item.getType()) {
        case ENDER_EYE: {
            if (phase != Phase.SEEK) {
                return;
            }
            itemCooldown.put(player.getUniqueId(), then);
            Component message = Component.text("All hiders are meowing...", NamedTextColor.GREEN);
            player.sendMessage(message);
            player.sendActionBar(message);
            hint();
            break;
        }
        case WHEAT: {
            event.setCancelled(true);
            if (phase != Phase.SEEK) {
                return;
            }
            if (hiders.contains(player.getUniqueId())) {
                summonDistraction(player);
                itemCooldown.put(player.getUniqueId(), then);
                Component message = Component.text("Summoning a distraction...", NamedTextColor.GREEN);
                player.sendMessage(message);
                player.sendActionBar(message);
            } else {
                Component message = Component.text("You're not hiding!", NamedTextColor.RED);
                player.sendMessage(message);
                player.sendActionBar(message);
            }
            break;
        }
        case RABBIT_FOOT:
            event.setCancelled(true);
            if (phase != Phase.SEEK && phase != Phase.HIDE) {
                return;
            }
            if (hiders.contains(player.getUniqueId())) {
                itemCooldown.put(player.getUniqueId(), then);
                Component message = Component.text("Rerolling disguise...", NamedTextColor.GREEN);
                player.sendMessage(message);
                player.sendActionBar(message);
                undisguise(player);
                disguise(player);
            } else {
                Component message = Component.text("You're not hiding!", NamedTextColor.GREEN);
                player.sendMessage(message);
                player.sendActionBar(message);
            }
            break;
        case SLIME_BALL:
            event.setCancelled(true);
            if (phase != Phase.SEEK && phase != Phase.HIDE) {
                return;
            }
            if (hiders.contains(player.getUniqueId())) {
                if (block == null) {
                    return;
                }
                if (block.isEmpty() || block.isLiquid()) {
                    return;
                }
                Material material = block.getType();
                switch (material) {
                case BARRIER:
                case CHEST:
                case TRAPPED_CHEST:
                case ENDER_CHEST:
                case LADDER:
                case VINE:
                case PLAYER_HEAD:
                    return;
                default:
                    if (org.bukkit.Tag.BUTTONS.isTagged(material)) return;
                    if (org.bukkit.Tag.SIGNS.isTagged(material)) return;
                    if (MaterialTags.SKULLS.isTagged(material)) return;
                    if (block.getBlockData() instanceof Bisected) return;
                    break;
                }
                itemCooldown.put(player.getUniqueId(), then);
                Component message = Component.text("Disguising as " + blockName(material), NamedTextColor.GREEN);
                player.sendMessage(message);
                player.sendActionBar(message);
                undisguise(player);
                disguise(player, material);
            } else {
                Component message = Component.text("You're not hiding!", NamedTextColor.RED);
                player.sendMessage(message);
                player.sendActionBar(message);
            }
            break;
        case GLASS:
            event.setCancelled(true);
            if (phase != Phase.SEEK) {
                return;
            }
            itemCooldown.put(player.getUniqueId(), then);
            useInvisItem(player);
            break;
        default: return;
        }
        event.setCancelled(true);
        item.subtract(1);
    }

    @EventHandler
    void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!isGameWorld(event.getBlock().getWorld())) return;
        event.setCancelled(true);
    }

    protected ItemStack hintEye(int amount) {
        return Items.text(new ItemStack(Material.ENDER_EYE, amount),
                          Component.text("Hint (Meow)", NamedTextColor.LIGHT_PURPLE),
                          Component.text("Make all hiders meow", NamedTextColor.GRAY),
                          Component.text("Right-Click", NamedTextColor.GREEN)
                          .append(Component.text(" to use", NamedTextColor.GRAY)));
    }

    protected ItemStack summonWheat(int amount) {
        return Items.text(new ItemStack(Material.WHEAT, amount),
                          Component.text("Summon distraction", NamedTextColor.LIGHT_PURPLE),
                          Component.text("Spawn in a mob near you"),
                          Component.text("Right-Click", NamedTextColor.GREEN)
                          .append(Component.text(" to use", NamedTextColor.GRAY)));
    }

    protected ItemStack rerollFoot(int amount) {
        return Items.text(new ItemStack(Material.RABBIT_FOOT, amount),
                          Component.text("Reroll disguise", NamedTextColor.LIGHT_PURPLE),
                          Component.text("Switch to a different", NamedTextColor.GRAY),
                          Component.text("random disguise", NamedTextColor.GRAY),
                          Component.text("Right-Click", NamedTextColor.GREEN)
                          .append(Component.text(" to use", NamedTextColor.GRAY)));
    }

    protected ItemStack copySlime(int amount) {
        return Items.text(new ItemStack(Material.SLIME_BALL, amount),
                          Component.text("Copy slime", NamedTextColor.GREEN),
                          Component.text("Disguise as a", NamedTextColor.GRAY),
                          Component.text("certain block", NamedTextColor.GRAY),
                          Component.text("Right-Click", NamedTextColor.GREEN)
                          .append(Component.text(" a block to use", NamedTextColor.GRAY)));
    }

    protected ItemStack makeInvisItem(int amount) {
        return Items.text(new ItemStack(Material.GLASS, amount),
                          Component.text("Become Invisible", NamedTextColor.LIGHT_PURPLE),
                          Component.text("Become invisible", NamedTextColor.GRAY),
                          Component.text("for 10 seconds", NamedTextColor.GRAY),
                          Component.text("Right-Click", NamedTextColor.GREEN)
                          .append(Component.text(" to vanish", NamedTextColor.GRAY)));
    }

    protected ItemStack makeCompass(int amount) {
        return Items.text(new ItemStack(Material.COMPASS, amount),
                          List.of(Component.text("Hider Finder", NamedTextColor.GOLD),
                                  Component.text("Points to the nearest", NamedTextColor.GRAY),
                                  Component.text("hider but goes wild", NamedTextColor.GRAY),
                                  Component.text("when you're too close", NamedTextColor.GRAY)));
    }

    protected void summonDistraction(Player player) {
        Enum enume = disguises.get(player.getUniqueId());
        EntityType type;
        if (enume instanceof EntityType) {
            type = (EntityType) enume;
        } else {
            List<EntityType> types = Arrays
                .asList(EntityType.BEE,
                        EntityType.COW,
                        EntityType.SHEEP,
                        EntityType.PIG,
                        EntityType.CAT,
                        EntityType.WOLF);
            type = types.get(random.nextInt(types.size()));
        }
        Entity entity = player.getWorld().spawn(player.getLocation(), type.getEntityClass(), e -> {
                e.setPersistent(false);
                if (e instanceof LivingEntity) {
                    ((LivingEntity) e).setRemoveWhenFarAway(true);
                    ((LivingEntity) e).setSilent(true);
                }
            });
        distractions.add(entity);
    }

    void useInvisItem(Player player) {
        if (!hiders.contains(player.getUniqueId())) {
            Component message = Component.text("You're not hiding!", NamedTextColor.RED);
            player.sendMessage(message);
            player.sendActionBar(message);
            return;
        }
        undisguise(player);
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 200, 1, true, false));
        getServer().getScheduler().runTaskLater(this, () -> {
                if (player.isValid() && hiders.contains(player.getUniqueId())) {
                    redisguise(player);
                }
            }, 200L);
        Component message = Component.text("Invisible for 10 seconds! GOGOGO", NamedTextColor.AQUA);
        player.sendMessage(message);
        player.sendActionBar(message);
    }

    @EventHandler
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        if (!isGameWorld(event.getEntity().getWorld())) return;
        if (event.getRemover() instanceof Player && ((Player) event.getRemover()).isOp()) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onHangingPlace(HangingPlaceEvent event) {
        if (!isGameWorld(event.getEntity().getWorld())) return;
        if (event.getPlayer().isOp()) return;
        event.setCancelled(true);
    }

    @EventHandler
    void onEntityDamage(EntityDamageEvent event) {
        if (!isGameWorld(event.getEntity().getWorld())) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
            teleporting = true;
            player.teleport(getHideLocation());
            teleporting = false;
            player.setFallDistance(0f);
            return;
        }
        if (phase != Phase.HIDE && phase != Phase.SEEK) return;
        event.setCancelled(true);
        if (!hiders.contains(player.getUniqueId())) return;
        switch (event.getCause()) {
        case FALL:
        case DROWNING:
            return;
        case FIRE:
        case LAVA:
            Bukkit.getScheduler().runTask(this, () -> {
                    player.setFireTicks(0);
                    player.sendMessage(Component.text("Burning returns you to spawn!",
                                                      NamedTextColor.RED));
                    teleporting = true;
                    player.teleport(getHideLocation());
                    teleporting = false;
                });
        default:
            break;
        }
    }

    @EventHandler
    void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!isGameWorld(player.getWorld())) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;
        player.sendMessage(Component.text("Item dropping not allowed!", NamedTextColor.RED));
        event.setCancelled(true);
    }

    private void computeHighscore() {
        highscore = Highscore.of(tag.scores);
    }

    private int rewardHighscore() {
        return Highscore.reward(tag.scores,
                                "hide_and_seek",
                                TrophyCategory.MEDAL,
                                TITLE,
                                hi -> "You collected " + hi.score + " point" + (hi.score == 1 ? "" : "s"));
    }
}
