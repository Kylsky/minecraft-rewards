package fun.yeelo;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Particle.DustOptions;
import org.bukkit.util.Vector;

import java.util.*;

public class Rewards extends JavaPlugin implements Listener {
    private FileConfiguration config;
    private Random random;
    private List<RewardItem> rewardItems;
    private Map<String, Enchantment> enchantmentMap;
    private long nextEventTime;
    private int TOTAL_METEORS;

    @Override
    public void onEnable() {
        this.getCommand("startmeteor").setExecutor(new MeteorShowerCommand());
        // 保存默认配置
        saveDefaultConfig();
        config = getConfig();
        TOTAL_METEORS = config.getInt("TOTAL_METEORS");
        random = new Random();
        rewardItems = new ArrayList<>();

        // 初始化附魔映射
        initEnchantmentMap();
        // 加载奖励物品
        loadRewardItems();

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("FishingRewards 插件已启用!");

        scheduleNextEvent();
        startEventChecker();
    }

    private void initEnchantmentMap() {
        enchantmentMap = new HashMap<>();
        enchantmentMap.put("DURABILITY", Enchantment.UNBREAKING);
        enchantmentMap.put("MENDING", Enchantment.MENDING);
        // 可以添加更多附魔类型
    }

    private void loadRewardItems() {
        List<String> items = config.getStringList("additional_rewards");
        for (String item : items) {
            String[] parts = item.split(":");
            if (parts.length >= 3) {
                // 解析物品类型和基本属性
                String itemType = parts[0];
                int amount = Integer.parseInt(parts[1]);
                double chance = Double.parseDouble(parts[2]);

                // 检查是否是附魔书
                if (itemType.startsWith("ENCHANTED_BOOK")) {
                    String[] enchantInfo = itemType.split("\\|");
                    if (enchantInfo.length >= 3) {
                        String enchantName = enchantInfo[1];
                        int enchantLevel = Integer.parseInt(enchantInfo[2]);
                        rewardItems.add(new RewardItem(Material.ENCHANTED_BOOK, amount, chance, enchantName, enchantLevel));
                    }
                } else {
                    Material material = Material.getMaterial(itemType);
                    rewardItems.add(new RewardItem(material, amount, chance, itemType.startsWith("DIAMOND_GEAR") ? "DIAMOND_GEAR" : null, 0));
                }
            }
        }
    }

    @EventHandler
    public void onPlayerFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            Player player = event.getPlayer();
            ItemStack fishingRod = player.getInventory().getItemInMainHand();
            Location fishLocation = event.getHook().getLocation();

            if (config.getBoolean("enable_additional_rewards", true)) {
                int luckOfTheSeaLevel = getLuckOfTheSeaLevel(fishingRod);
                giveAdditionalRewards(player, luckOfTheSeaLevel, fishLocation);
            }
        }
    }

    private int getLuckOfTheSeaLevel(ItemStack fishingRod) {
        if (fishingRod != null && fishingRod.getType() == Material.FISHING_ROD) {
            ItemMeta meta = fishingRod.getItemMeta();
            if (meta != null && meta.hasEnchant(Enchantment.LUCK_OF_THE_SEA)) {
                return meta.getEnchantLevel(Enchantment.LUCK_OF_THE_SEA);
            }
        }
        return 0;
    }

    private void giveAdditionalRewards(Player player, int luckLevel, Location fishLocation) {
        // 获取各种加成倍率
        double baseMultiplier = config.getDouble("base_multiplier", 1.0);
        double enchantMultiplier = config.getDouble("enchant_multiplier", 0.3);
        double weatherMultiplier = getWeatherMultiplier(player.getWorld());
        double finalMultiplier = (baseMultiplier + (luckLevel * enchantMultiplier)) * weatherMultiplier;

        for (RewardItem reward : rewardItems) {
            double adjustedChance = Math.min(reward.getChance() * finalMultiplier, 100.0);

            if (random.nextDouble() * 100 <= adjustedChance) {
                ItemStack rewardStack;
                String itemName;
                if (reward.isEnchantedBook()) {
                    rewardStack = createEnchantedBook(reward.getEnchantName(), reward.getEnchantLevel());
                    itemName = getEnchantedBookName(reward.getEnchantName(), reward.getEnchantLevel());
                } else if ("DIAMOND_GEAR".equals(reward.getEnchantName())) {
                    rewardStack = createDiamondGear("海洋赐福的", 1, "从深海中打捞的远古装备");
                    itemName = rewardStack.getItemMeta().getDisplayName();
                } else {
                    rewardStack = new ItemStack(reward.getMaterial(), reward.getAmount());
                    itemName = getChineseName(reward.getMaterial());
                }

                // 给予物品
                player.getInventory().addItem(rewardStack);

                // 发送私人消息
                StringBuilder message = new StringBuilder(ChatColor.GREEN + "额外奖励: " +
                                                                  reward.getAmount() + "个" + itemName);

                // 添加加成信息
                List<String> bonusInfo = new ArrayList<>();
                if (luckLevel > 0) {
                    bonusInfo.add("海之眷顾 " + luckLevel);
                }
                if (weatherMultiplier > 1.0) {
                    bonusInfo.add("雨天加成");
                }

                if (!bonusInfo.isEmpty()) {
                    message.append(ChatColor.AQUA + " (")
                            .append(String.join(", ", bonusInfo))
                            .append(" 加成)");
                }

                // 广播消息
                broadcastReward(player, reward, itemName);

                // 播放效果
                playRewardEffect(fishLocation, reward.getMaterial());
                playRewardSound(player, fishLocation, reward.getChance());
            }
        }
    }

    /**
     * @param desc 装备说明
     * @param type 1海洋2大地3天空4梦境
     * @description: TODO
     * @author Kongyl
     * @date 2024/11/24 10:17
     */
    private ItemStack createDiamondGear(String desc, Integer type, String enchant) {
        // 随机选择一个钻石装备类型
        Material[] diamondGear = {
                Material.DIAMOND_SWORD,
                Material.DIAMOND_PICKAXE,
                Material.DIAMOND_AXE,
                Material.DIAMOND_HELMET,
                Material.DIAMOND_CHESTPLATE,
                Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_BOOTS
        };

        Material chosenGear = diamondGear[random.nextInt(diamondGear.length)];
        ItemStack item = new ItemStack(chosenGear);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            // 添加名称
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + desc + getChineseName(chosenGear));

            // 添加lore
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + enchant);
            String getWhileDo = "";
            switch (type) {
                case 1:
                    getWhileDo = "抛竿钓鱼";
                    break;
                case 2:
                    getWhileDo = "发掘地脉";
                    break;
                case 3:
                    getWhileDo = "仰望天空";
                    break;
                default:
                    getWhileDo = "沉眠梦境";
            }
            lore.add(ChatColor.AQUA + getWhileDo + "时获得");
            meta.setLore(lore);

            // 根据装备类型添加对应的附魔
            if (chosenGear == Material.DIAMOND_SWORD) {
                meta.addEnchant(Enchantment.UNBREAKING, 3, true);                               // 耐久 III
                meta.addEnchant(Enchantment.SHARPNESS, type == 2 ? 4 : 5, true);                  // 锋利 V
                meta.addEnchant(Enchantment.SMITE, type == 2 ? 4 : 5, true);                      // 亡灵杀手 V
                meta.addEnchant(Enchantment.BANE_OF_ARTHROPODS, type == 2 ? 4 : 5, true);         // 节肢杀手 V
                meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);                              // 火焰附加 II
                meta.addEnchant(Enchantment.LOOTING, 3, true);                                  // 抢夺 III
                meta.addEnchant(Enchantment.SWEEPING_EDGE, 3, true);                            // 横扫之刃 III
                meta.addEnchant(Enchantment.MENDING, 1, true);                                  // 经验修补
                meta.addEnchant(Enchantment.KNOCKBACK, 2, true);                                // 击退
            } else if (chosenGear == Material.DIAMOND_PICKAXE) {
                meta.addEnchant(Enchantment.UNBREAKING, 3, true);                               // 耐久 III
                meta.addEnchant(Enchantment.EFFICIENCY, 5, true);                               // 效率 V
                meta.addEnchant(Enchantment.FORTUNE, type == 2 ? 4 : 3, true);                    // 时运 III-大地装备+1
                meta.addEnchant(Enchantment.MENDING, 1, true);                                  // 经验修补
            } else if (chosenGear == Material.DIAMOND_AXE) {
                meta.addEnchant(Enchantment.UNBREAKING, 3, true);                               // 耐久 III
                meta.addEnchant(Enchantment.EFFICIENCY, 5, true);                               // 效率 V
                meta.addEnchant(Enchantment.SHARPNESS, 5, true);                                // 锋利 V
                meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);                              // 火焰附加 II
                meta.addEnchant(Enchantment.KNOCKBACK, 2, true);                                // 击退
                meta.addEnchant(Enchantment.MENDING, 1, true);                                  // 经验修补
            } else if (isArmor(chosenGear)) {
                meta.addEnchant(Enchantment.UNBREAKING, 3, true);                               // 耐久 III
                meta.addEnchant(Enchantment.PROTECTION, type == 2 ? 5 : (type==4 ? 6 : 4), true);                 // 保护 IV-大地装备+1 沉眠梦境+2
                meta.addEnchant(Enchantment.FIRE_PROTECTION, type == 4 ? 5 : 4, true);            // 火焰保护 IV-沉眠梦境+1
                meta.addEnchant(Enchantment.BLAST_PROTECTION, type == 4 ? 5 : 4, true);           // 爆炸保护 IV-沉眠梦境+1
                meta.addEnchant(Enchantment.PROJECTILE_PROTECTION, type == 4 ? 5 : 4, true);      // 弹射物保护 IV-沉眠梦境+1
                meta.addEnchant(Enchantment.THORNS, type == 2 ? 4 : 3, true);                     // 荆棘 III-沉眠梦境+1
                meta.addEnchant(Enchantment.MENDING, 1, true);                                  // 经验修补

                // 特殊处理靴子
                if (chosenGear == Material.DIAMOND_BOOTS) {
                    meta.addEnchant(Enchantment.DEPTH_STRIDER, type == 1 ? 5 : 3, true);           // 深海探索者 III-海洋装备+2
                    meta.addEnchant(Enchantment.FEATHER_FALLING, type == 3 ? 6 : 4, true);         // 摔落保护 IV-天空装备+2
                    meta.addEnchant(Enchantment.SOUL_SPEED, 3, true);                           // 灵魂疾行
                }
                // 特殊处理裤子
                if (chosenGear == Material.DIAMOND_BOOTS) {
                    meta.addEnchant(Enchantment.SWIFT_SNEAK, 3, true); // 迅捷前行
                }
                if (chosenGear == Material.DIAMOND_HELMET) {
                    meta.addEnchant(Enchantment.AQUA_AFFINITY, type == 2 ? 3 : 1, true);            // 水下速掘 I-海洋装备+2
                    meta.addEnchant(Enchantment.RESPIRATION, type == 2 ? 5 : 3, true);              // 水下呼吸 III-海洋装备+2
                }
            }

            item.setItemMeta(meta);
        }

        return item;
    }

    private boolean isArmor(Material material) {
        return material == Material.DIAMOND_HELMET ||
                       material == Material.DIAMOND_CHESTPLATE ||
                       material == Material.DIAMOND_LEGGINGS ||
                       material == Material.DIAMOND_BOOTS;
    }

    private ItemStack createEnchantedBook(String enchantName, int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();

        Enchantment enchantment = enchantmentMap.get(enchantName);
        if (enchantment != null && meta != null) {
            meta.addStoredEnchant(enchantment, level, true);
            book.setItemMeta(meta);
        }

        return book;
    }

    /**
     * @description: TODO
     * @param player 玩家
     * @param reward 奖励
     * @param itemName 物品名称
     * @author Kongyl
     * @date 2024/11/24 10:50
     */
    private void broadcastReward(Player player, RewardItem reward, String itemName) {
        if (!config.getBoolean("enable_broadcast", true)) {
            return;
        }

        Server server = getServer();
        String playerName = player.getName();
        String broadcastMessage = "";

        if (reward.getChance() <= 0.1) {  // 超稀有物品
            broadcastMessage = String.format(
                    "\n" +
                            ChatColor.GOLD + "★★★★★★★★★★★★★★★★★★★★\n" +
                            ChatColor.YELLOW + "恭喜玩家 " + ChatColor.RED + "%s" +
                            ChatColor.YELLOW + " 在钓鱼时获得了超稀有物品：\n" +
                            ChatColor.LIGHT_PURPLE + "%d个" + ChatColor.BOLD + "%s" +
                            ChatColor.YELLOW + " [%s%%机率]\n" +
                            ChatColor.GOLD + "★★★★★★★★★★★★★★★★★★★★\n",
                    playerName, reward.getAmount(), itemName, reward.getChance()
            );

            // 全服特殊音效
            for (Player p : server.getOnlinePlayers()) {
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            }
        } else if (reward.getChance() <= 1.0) {  // 稀有物品
            broadcastMessage = String.format(
                    ChatColor.GOLD + "✮ " +
                            ChatColor.YELLOW + "恭喜玩家 " + ChatColor.RED + "%s" +
                            ChatColor.YELLOW + " 钓到了稀有物品：" +
                            ChatColor.LIGHT_PURPLE + "%d个%s" +
                            ChatColor.YELLOW + " [%s%%机率]" +
                            ChatColor.GOLD + " ✮",
                    playerName, reward.getAmount(), itemName, reward.getChance()
            );

            // 全服普通音效
            for (Player p : server.getOnlinePlayers()) {
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            }
        } else if (reward.getChance() <= 5.0) {  // 较少见物品
            //broadcastMessage = String.format(
            //        ChatColor.YELLOW + "恭喜玩家 " + ChatColor.GOLD + "%s" +
            //                ChatColor.YELLOW + " 钓到了：" +
            //                ChatColor.WHITE + "%d个%s",
            //        playerName, reward.getAmount(), itemName
            //);
        } else {  // 普通物品
            if (!config.getBoolean("broadcast_common_items", false)) {
                return;
            }
            //broadcastMessage = String.format(
            //        ChatColor.GRAY + "玩家 %s 钓到了 %d个%s",
            //        playerName, reward.getAmount(), itemName
            //);
        }
        if (!broadcastMessage.isEmpty()) {
            server.broadcastMessage(broadcastMessage);
        }
    }

    private void playRewardSound(Player player, Location location, double chance) {
        if (!config.getBoolean("enable_sounds", true)) {
            return;
        }

        if (chance <= 0.1) {  // 超稀有
            player.playSound(location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        } else if (chance <= 1.0) {  // 稀有
            player.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        } else {  // 普通
            player.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }
    }

    // 粒子特效
    private void playRewardEffect(Location location, Material material) {
        if (!config.getBoolean("enable_particles", true)) {
            return;
        }

        // 创建一个上升的螺旋粒子效果
        new BukkitRunnable() {
            double angle = 0;
            double y = 0;
            int iterations = 0;

            @Override
            public void run() {
                if (iterations > 20) {  // 持续1秒（20刻）
                    this.cancel();
                    return;
                }

                // 获取物品对应的颜色
                Color color = getColorForMaterial(material);
                DustOptions dustOptions = new DustOptions(color, 1.0f);

                // 创建螺旋效果
                double radius = 0.5;
                for (int i = 0; i < 2; i++) {  // 每帧创建2个粒子点
                    double x = radius * Math.cos(angle);
                    double z = radius * Math.sin(angle);
                    Location particleLoc = location.clone().add(x, y, z);

                    // 发射粒子
                    location.getWorld().spawnParticle(
                            Particle.CLOUD,
                            particleLoc,
                            1,
                            0, 0, 0,
                            0
                    );

                    // 添加闪光效果
                    if (random.nextDouble() < 0.3) {  // 30%的概率产生闪光
                        location.getWorld().spawnParticle(
                                Particle.GLOW,
                                particleLoc,
                                1,
                                0.1, 0.1, 0.1,
                                0
                        );
                    }

                    angle += Math.PI / 8;
                }

                y += 0.1;  // 每帧上升0.1格
                iterations++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private double getWeatherMultiplier(World world) {
        if (world.hasStorm()) {  // 检查是否在下雨
            double rainMultiplier = config.getDouble("rain_multiplier", 2.0);
            if (world.isThundering()) {  // 如果是雷暴天气，可以给予更高的加成
                double thunderMultiplier = config.getDouble("thunder_multiplier", 2.5);
                return thunderMultiplier;
            }
            return rainMultiplier;
        }
        return 1.0;  // 晴天无加成
    }

    private void playWeatherBonusEffect(Location location) {
        if (!config.getBoolean("enable_particles", true)) {
            return;
        }

        // 为雨天加成添加特殊的水滴粒子效果
        new BukkitRunnable() {
            int iterations = 0;

            @Override
            public void run() {
                if (iterations > 20) {
                    this.cancel();
                    return;
                }

                // 在钓鱼点周围产生水滴效果
                location.getWorld().spawnParticle(
                        Particle.DRIPPING_WATER,
                        location,
                        10,  // 粒子数量
                        0.5, 0.5, 0.5,  // 扩散范围
                        0  // 粒子速度
                );

                iterations++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private Color getColorForMaterial(Material material) {
        if (material == null) {
            return Color.fromRGB(255, 255, 255);
        }
        switch (material) {
            case SEA_LANTERN:
            case DIAMOND_BLOCK:
            case DIAMOND:
            case ENCHANTED_GOLDEN_APPLE:
                return Color.fromRGB(51, 235, 203);
            case GOLDEN_APPLE:
            case EMERALD:
                return Color.fromRGB(17, 160, 50);
            case GOLD_BLOCK:
            case GOLD_INGOT:
                return Color.fromRGB(255, 215, 0);
            case IRON_INGOT:
                return Color.fromRGB(213, 213, 213);
            case ENCHANTED_BOOK:
                return Color.fromRGB(128, 0, 128);
            case EXPERIENCE_BOTTLE:
                return Color.fromRGB(128, 255, 32);
            default:
                return Color.fromRGB(255, 255, 255);
        }
    }

    private String getChineseName(Material material) {
        return config.getString("item_names." + material.name(), material.name());
    }

    private String getEnchantedBookName(String enchantName, int level) {
        return config.getString("enchant_names." + enchantName, enchantName) + " " +
                       toRomanNumeral(level) + " 附魔书";
    }

    private String toRomanNumeral(int number) {
        String[] romanNumerals = {"I", "II", "III", "IV", "V"};
        return number > 0 && number <= romanNumerals.length ? romanNumerals[number - 1] : String.valueOf(number);
    }

    private static class RewardItem {
        private final Material material;
        private final int amount;
        private final double chance;
        private final String enchantName;
        private final int enchantLevel;

        public RewardItem(Material material, int amount, double chance) {
            this(material, amount, chance, null, 0);
        }

        public RewardItem(Material material, int amount, double chance, String enchantName, int enchantLevel) {
            this.material = material;
            this.amount = amount;
            this.chance = chance;
            this.enchantName = enchantName;
            this.enchantLevel = enchantLevel;
        }

        public boolean isEnchantedBook() {
            return material == Material.ENCHANTED_BOOK && enchantName != null;
        }

        public Material getMaterial() {
            return material;
        }

        public int getAmount() {
            return amount;
        }

        public double getChance() {
            return chance;
        }

        public String getEnchantName() {
            return enchantName;
        }

        public int getEnchantLevel() {
            return enchantLevel;
        }
    }

    private void startEventChecker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                World world = Bukkit.getWorlds().get(0);
                for (World w : Bukkit.getWorlds()) {
                    if (world.getEnvironment() == World.Environment.NORMAL) {
                        world = w;
                        break;
                    }
                }

                long currentTime = world.getFullTime();
                if (currentTime >= nextEventTime && !world.getPlayers().isEmpty()) {
                    boolean res = triggerMeteorShower(world);
                    if (res) {
                        scheduleNextEvent();
                    }
                } else {
                    getLogger().info("流星雨检测-流星雨等待中,当前时间：" + currentTime + "下一次流星雨时间：" + nextEventTime);
                }
            }
        }.runTaskTimer(this, 1200L, 1200L);
    }

    private void scheduleNextEvent() {
        World world = Bukkit.getWorlds().get(0); // 获取主世界
        long currentTime = world.getFullTime();
        long daysUntilNext = 7 + random.nextInt(7); // 2-4天随机
        long ticksUntilNext = daysUntilNext * 24000;

        nextEventTime = currentTime + ticksUntilNext;
        getLogger().info(String.format("下一次流星雨将在 %d 天后发生", daysUntilNext));
    }

    private void createMeteor(Location start, List<Location> rewardLocations ,int meteorCount, int rewardCount) {
        new BukkitRunnable() {
            Location current = start.clone();
            Vector direction = new Vector(
                    random.nextDouble() - 0.5,  // 随机X方向
                    -1,                         // 向下移动
                    random.nextDouble() - 0.5   // 随机Z方向
            ).normalize().multiply(1.0);    // 调整速度

            int ticks = 0;

            @Override
            public void run() {
                // 只检查是否撞到实体方块,去掉MAX_TICKS检查
                if (current.getBlock().getType().isSolid()) {
                    this.cancel();

                    // 生成随机奖励物品

                    // 添加陨石撞击效果
                    World world = current.getWorld();
                    world.spawnParticle(Particle.FLAME, current, 15, 0.1, 0.1, 0.1, 0.01);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, current, 5, 0.05, 0.05, 0.05, 0.01);

                    // 撞击音效
                    world.playSound(current, Sound.ENTITY_PHANTOM_SWOOP, 1.5f, 0.6f);
                    world.playSound(current, Sound.BLOCK_CHEST_CLOSE, 1.0f, 0.8f);

                    fillChestWithRewards(current.clone(), meteorCount,rewardCount);

                    return;
                }

                // 增加安全检查:如果Y坐标太低,强制寻找地面
                if (current.getY() < 0) {
                    Location groundLoc = findGroundLocation(current);
                    if (groundLoc != null) {
                        rewardLocations.add(groundLoc);

                        // 在地面生成撞击效果
                        World world = groundLoc.getWorld();
                        world.spawnParticle(Particle.EXPLOSION, groundLoc, 1, 0, 0, 0, 0);
                        world.spawnParticle(Particle.FLAME, groundLoc, 30, 0.5, 0.5, 0.5, 0.1);
                        world.playSound(groundLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);

                        // 发送消息
                        Bukkit.broadcastMessage(ChatColor.GOLD + "§l[流星宝箱] §r§e一个流星宝箱降临在: "
                                                        + ChatColor.AQUA + "X:" + groundLoc.getBlockX()
                                                        + " Y:" + groundLoc.getBlockY()
                                                        + " Z:" + groundLoc.getBlockZ());
                    }
                    this.cancel();
                    return;
                }

                World world = start.getWorld();
                // 生成粒子效果
                world.spawnParticle(Particle.FLAME, current, 20, 0.3, 0.3, 0.3, 0.05);
                world.spawnParticle(Particle.SMOKE, current, 20, 0.3, 0.3, 0.3, 0.05);
                world.spawnParticle(Particle.LAVA, current, 5, 0.1, 0.1, 0.1, 0);
                world.spawnParticle(Particle.GLOW, current, 10, 0.2, 0.2, 0.2, 0);

                if (ticks % 5 == 0) {
                    world.playSound(current, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 0.5f);
                    world.playSound(current, Sound.BLOCK_FIRE_AMBIENT, 0.8f, 1.0f);
                }

                current.add(direction);
                ticks++;

            }
        }.runTaskTimer(this, 0L, 1L);
    }

    // 添加一个新方法来寻找地面位置
    private Location findGroundLocation(Location current) {
        Location checkLoc = current.clone();
        checkLoc.setY(current.getWorld().getMaxHeight() - 1);

        // 从最高处向下寻找第一个实体方块
        while (checkLoc.getY() > 0) {
            if (checkLoc.getBlock().getType().isSolid()) {
                // 找到地面,返回其上方一格的位置
                return checkLoc.add(0, 1, 0);
            }
            checkLoc.subtract(0, 1, 0);
        }

        return null;  // 如果找不到地面,返回null
    }

    @EventHandler
    public boolean onDiamondBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.DIAMOND_ORE && block.getType() != Material.DEEPSLATE_DIAMOND_ORE) {
            return false;
        }

        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();

        // 基础概率 0.5% = 0.005
        double chance = 0.005;

        // 检查时运等级
        if (tool.hasItemMeta() && tool.getItemMeta().hasEnchant(Enchantment.LOOTING)) {
            int fortuneLevel = tool.getItemMeta().getEnchantLevel(Enchantment.LOOTING);
            if (fortuneLevel >= 3) {
                // 时运3使概率翻3倍
                chance *= fortuneLevel;
            }
        }

        if (Math.random() < chance) {

            ItemStack drop = createDiamondGear("大地赐福的", 2, "从地心中发掘的远古装备");

            // 掉落装备
            block.getWorld().dropItemNaturally(block.getLocation(), drop);

            // 播放特殊音效和粒子效果表示稀有掉落
            Location loc = block.getLocation();
            World world = block.getWorld();
            world.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            world.spawnParticle(Particle.FLASH, loc, 30, 0.5, 0.5, 0.5, 0.1);

            // 可以给玩家发送消息
            player.sendMessage(ChatColor.GREEN + "你挖到了一件稀有的钻石装备！");
            String broadcastMessage = String.format(
                    ChatColor.YELLOW + "恭喜玩家 " + ChatColor.GOLD + "%s" +
                            ChatColor.YELLOW + " 在矿物中发现了：" +
                            ChatColor.WHITE + "%d个%s",
                    player.getName(), drop.getAmount(), Objects.requireNonNull(drop.getItemMeta()).getDisplayName()
            );
            getServer().broadcastMessage(broadcastMessage);

            return true;
        }

        return false;
    }

    private void createMeteorShowerEffect(Player player, int rewardCount, int meteorCount) {
        World world = player.getWorld();
        Location playerLoc = player.getLocation();

        new BukkitRunnable() {
            int meteorsCreated = 0;
            List<Location> rewardLocations = new ArrayList<>();

            @Override
            public void run() {
                if (meteorsCreated >= TOTAL_METEORS) {
                    this.cancel();
                    return;
                }

                // 在更大范围内生成流星
                double x = playerLoc.getX() + (random.nextInt(150) - 50);
                double z = playerLoc.getZ() + (random.nextInt(150) - 50);
                Location meteorStart = new Location(world, x, playerLoc.getY() + 120, z);  // 增加起始高度

                createMeteor(meteorStart, rewardLocations,meteorCount,rewardCount);
                meteorsCreated++;

                // 给所有玩家发送标题提醒
                if (meteorsCreated == 1) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.sendTitle(
                                "§6☄ 流星雨来临 ☄",
                                "§f天空中划过璀璨的光芒...",
                                10, 60, 20
                        );
                    }
                }
            }
        }.runTaskTimer(this, 0L, 10L);  // 减少生成间隔
    }

    public Boolean triggerMeteorShower(World world) {
        List<Player> worldPlayers = new ArrayList<>(world.getPlayers());
        if (worldPlayers.isEmpty()) return false;

        // 判断是不是晚上
        long time = world.getTime();
        if (time < 13000 || time > 23000) {
            getLogger().info("流星雨检测-未达夜晚");
            return false;
        }

        // 判断是否下雨或者雷暴
        if (world.hasStorm() || world.isThundering()) {
            getLogger().info("流星雨检测-正在下雨或雷暴中");
            return false;
        }

        // 选择中心玩家并开始事件
        Player centralPlayer = worldPlayers.get(random.nextInt(worldPlayers.size()));
        getLogger().info("选中玩家为：" + centralPlayer.getName());
        int rewardCount = 0;
        getServer().broadcastMessage(String.format(
                ChatColor.GOLD + "✮ 天空响应了玩家：" + ChatColor.YELLOW + "%s 的召唤！" + ChatColor.GOLD + " ✮", centralPlayer.getName()));


        // 发送更醒目的公告
        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage("§6✧═══════════════════════✧");
        Bukkit.broadcastMessage("§b      流星雨事件开始！");
        Bukkit.broadcastMessage("§f  璀璨的流星正从天空坠落...");
        Bukkit.broadcastMessage("§e  注意观察四周，寻找珍贵的陨石！");
        Bukkit.broadcastMessage("§6✧═══════════════════════✧");
        Bukkit.broadcastMessage("");
        int meteorCount = 0;



        // 播放全服音效
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.MUSIC_CREDITS, 1.0f, 1.0f);
        }

        createMeteorShowerEffect(centralPlayer, rewardCount, meteorCount);
        return true;
    }

    private void spawnRewards(List<Location> locations, int rewardCount) {
        rewardCount = Math.min(rewardCount, locations.size());

        for (int i = 0; i < rewardCount; i++) {
            Location rewardLoc = locations.get(random.nextInt(locations.size()));
            locations.remove(rewardLoc);
            spawnReward(rewardLoc);
        }
    }

    private void spawnReward(Location location) {
        ItemStack[] possibleRewards = {
                new ItemStack(Material.DIAMOND, random.nextInt(3) + 1),
                new ItemStack(Material.EMERALD, random.nextInt(4) + 1),
                new ItemStack(Material.NETHERITE_SCRAP, 1),
                new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1)
        };

        ItemStack reward = possibleRewards[random.nextInt(possibleRewards.length)];
        World world = location.getWorld();
        world.dropItemNaturally(location, reward);

        world.spawnParticle(Particle.GLOW, location, 50, 0.5, 0.5, 0.5, 0.1);
        world.playSound(location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.0f);
    }

    @Override
    public void onDisable() {
        getLogger().info("流星雨插件已关闭！");
    }

    // 添加填充箱子的方法
    private void fillChestWithRewards(Location location, int meteorCount, int rewardCount) {
        // 计算生成概率
        if (random.nextDouble() * 100 > 0.5) {
            return;
        }

        // 生成箱子
        location.add(0,1,0).getBlock().setType(Material.CHEST);
        Chest chest = (Chest) location.getBlock().getState();
        Inventory inv = chest.getInventory();

        // 定义可能的奖励物品及其权重
        Map<ItemStack, Double> rewards = new HashMap<>();
        rewards.put(new ItemStack(Material.DIAMOND, random.nextInt(3) + 1), 0.7);
        rewards.put(new ItemStack(Material.EMERALD, random.nextInt(5) + 1), 0.8);
        rewards.put(new ItemStack(Material.GOLDEN_APPLE, random.nextInt(2) + 1), 0.5);
        rewards.put(new ItemStack(Material.NETHERITE_SCRAP, 1), 0.2);
        rewards.put(new ItemStack(Material.EXPERIENCE_BOTTLE, random.nextInt(8) + 1), 0.9);
        rewards.put(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1), 0.1);

        // 随机选择3-6个物品放入箱子
        int itemCount = random.nextInt(4) + 3;
        List<ItemStack> selectedRewards = new ArrayList<>();

        // 根据权重选择物品
        for (Map.Entry<ItemStack, Double> entry : rewards.entrySet()) {
            if (random.nextDouble() < entry.getValue()) {
                selectedRewards.add(entry.getKey().clone());
            }
        }

        // 如果选中的物品太多，随机移除一些
        while (selectedRewards.size() > itemCount) {
            selectedRewards.remove(random.nextInt(selectedRewards.size()));
        }

        // 如果选中的物品太少，添加一些基础物品
        while (selectedRewards.size() < itemCount) {
            selectedRewards.add(new ItemStack(Material.IRON_INGOT, random.nextInt(3) + 1));
        }

        // 随机放置物品
        for (ItemStack item : selectedRewards) {
            int slot = random.nextInt(27);
            while (inv.getItem(slot) != null) {
                slot = (slot + 1) % 27;
            }
            inv.setItem(slot, item);
        }

        // 发送全服通知
        Bukkit.broadcastMessage(ChatColor.GOLD + "§l[流星宝箱] §r§e一个流星宝箱降临在: "
                                        + ChatColor.AQUA + "X:" + location.getBlockX()
                                        + " Y:" + location.getBlockY()
                                        + " Z:" + location.getBlockZ());

    }
}