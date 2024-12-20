package fun.yeelo;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MeteorShowerCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c只有玩家才能执行这个指令！");
            return true;
        }

        Player player = (Player) sender;

        // 检查是否为服主（OP）
        if (!player.isOp()) {
            player.sendMessage("§c只有服主才能执行这个指令！");
            return true;
        }

        // 检查参数
        if (args.length != 1) {
            player.sendMessage("§e用法: /startmeteor <流星雨数量>");
            return true;
        }

        // 解析参数
        int meteorCount;
        try {
            meteorCount = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage("§c请输入有效的流星雨数量！");
            return true;
        }

        // 开始流星雨效果
        player.sendMessage("§a正在为你启动流星雨效果...");


        startMeteorShower(player, meteorCount);
        return true;
    }

    private void startMeteorShower(Player player, int meteorCount) {
        // 获取插件主类实例（假设是 Main 类）
        Rewards plugin = (Rewards) Bukkit.getPluginManager().getPlugin("FishingRewards");

        if (plugin == null) {
            player.sendMessage("§c插件加载出错，无法启动流星雨！");
            return;
        }
        // 调用流星雨方法
        World world = Bukkit.getWorlds().get(0);
        for (World w : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                world = w;
                break;
            }
        }
        plugin.triggerMeteorShower(world);
    }


}
