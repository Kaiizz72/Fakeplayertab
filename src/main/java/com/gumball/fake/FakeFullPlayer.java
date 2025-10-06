package com.gumball.fake;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class FakeFullPlayer extends JavaPlugin implements CommandExecutor {
    private ProtocolManager protocolManager;
    private final Map<UUID, FakeInfo> fakes = new HashMap<>();
    private int nextEntityId = 2000000;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        protocolManager = ProtocolLibrary.getProtocolManager();
        this.getCommand("fakespawn").setExecutor(this);
        this.getCommand("fakeremove").setExecutor(this);
        this.getCommand("fakeremoveall").setExecutor(this);
        this.getCommand("fakechat").setExecutor(this);
        getLogger().info("FakeFullPlayer enabled.");
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(fakes.keySet())) {
            removeFakeToAll(id);
        }
        fakes.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (label.equalsIgnoreCase("fakespawn")) {
            if (!(sender instanceof Player)) { sender.sendMessage("Chỉ player mới dùng được."); return true; }
            if (args.length < 1) { sender.sendMessage("/fakespawn <name>"); return true; }
            Player p = (Player)sender;
            String name = args[0];
            UUID uuid = UUID.randomUUID();
            int entityId = nextEntityId++;
            Location loc = p.getLocation();

            FakeInfo info = new FakeInfo(uuid, name, entityId, loc);
            fakes.put(uuid, info);
            addFakeToAll(uuid, name, entityId, loc);

            // start auto chat task
            int base = getConfig().getInt("chat-interval", 30);
            int jitter = getConfig().getInt("chat-jitter", 0);
            int delay = base + new Random().nextInt(Math.max(1, jitter));
            int repeat = base;
            int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
                List<String> msgs = getConfig().getStringList("messages");
                if (msgs.isEmpty()) return;
                String msg = msgs.get(new Random().nextInt(msgs.size()));
                String prefix = getPrefixForFake(info.name);
                String fullMsg = (prefix!=null?prefix:"") + info.name + "§7: §f" + msg;
                for (Player viewer : Bukkit.getOnlinePlayers()) viewer.sendMessage(fullMsg);
            }, 20L*delay, 20L*repeat);
            info.taskId = taskId;

            sender.sendMessage("Spawned fake " + name + " ("+uuid+")");
            return true;
        }

        if (label.equalsIgnoreCase("fakeremove")) {
            if (args.length < 1) { sender.sendMessage("/fakeremove <uuid|name>"); return true; }
            String input = args[0];
            UUID found = null;
            try { found = UUID.fromString(input); }
            catch (IllegalArgumentException e) {
                for (UUID id : fakes.keySet()) {
                    if (fakes.get(id).name.equalsIgnoreCase(input)) { found = id; break; }
                }
            }
            if (found==null || !fakes.containsKey(found)) { sender.sendMessage("Không tìm thấy fake."); return true; }
            removeFakeToAll(found);
            fakes.remove(found);
            sender.sendMessage("Đã xóa fake " + input);
            return true;
        }

        if (label.equalsIgnoreCase("fakeremoveall")) {
            for (UUID id : new ArrayList<>(fakes.keySet())) {
                removeFakeToAll(id);
                fakes.remove(id);
            }
            sender.sendMessage("Đã xóa tất cả fake.");
            return true;
        }

        if (label.equalsIgnoreCase("fakechat")) {
            if (args.length < 2) { sender.sendMessage("/fakechat <name> <message>"); return true; }
            String name = args[0];
            String msg = String.join(" ", Arrays.copyOfRange(args,1,args.length));
            FakeInfo target = fakes.values().stream().filter(f->f.name.equalsIgnoreCase(name)).findFirst().orElse(null);
            if (target==null) { sender.sendMessage("Không tìm thấy fake."); return true; }
            String prefix = getPrefixForFake(target.name);
            String fullMsg = (prefix!=null?prefix:"") + target.name + "§7: §f" + msg;
            for (Player viewer : Bukkit.getOnlinePlayers()) viewer.sendMessage(fullMsg);
            return true;
        }
        return false;
    }

    private String getPrefixForFake(String name) {
        String raw = getConfig().getString("prefixes."+name);
        if (raw!=null) return raw.replace("&","§");
        String def = getConfig().getString("default-prefix","");
        return def.replace("&","§");
    }

    private void addFakeToAll(UUID uuid, String name, int entityId, Location loc) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            try {
                PacketContainer infoAdd = createAddPacket(uuid, name);
                protocolManager.sendServerPacket(viewer, infoAdd);
                PacketContainer spawn = createNamedEntitySpawn(entityId, uuid, loc);
                protocolManager.sendServerPacket(viewer, spawn);
            } catch(Exception e) { e.printStackTrace(); }
        }
    }

    private void removeFakeToAll(UUID uuid) {
        FakeInfo fi = fakes.get(uuid);
        if (fi==null) return;
        if (fi.taskId!=-1) Bukkit.getScheduler().cancelTask(fi.taskId);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            try {
                PacketContainer infoRem = createRemovePacket(uuid);
                protocolManager.sendServerPacket(viewer, infoRem);
                PacketContainer destroy = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
                destroy.getIntLists().write(0, Arrays.asList(fi.entityId));
                protocolManager.sendServerPacket(viewer, destroy);
            } catch(Exception e) { e.printStackTrace(); }
        }
    }

    private PacketContainer createAddPacket(UUID uuid, String name) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO);
        packet.getPlayerInfoActions().write(0, EnumWrappers.PlayerInfoAction.ADD_PLAYER);
        WrappedGameProfile profile = new WrappedGameProfile(uuid, name);
        String prefix = getPrefixForFake(name);
        WrappedChatComponent comp = WrappedChatComponent.fromText(prefix+name);
        PlayerInfoData data = new PlayerInfoData(profile, 1, EnumWrappers.NativeGameMode.SURVIVAL, comp);
        packet.getPlayerInfoDataLists().write(0, Collections.singletonList(data));
        return packet;
    }

    private PacketContainer createRemovePacket(UUID uuid) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO);
        packet.getPlayerInfoActions().write(0, EnumWrappers.PlayerInfoAction.REMOVE_PLAYER);
        WrappedGameProfile profile = new WrappedGameProfile(uuid, "remove-temp");
        PlayerInfoData data = new PlayerInfoData(profile, 1, EnumWrappers.NativeGameMode.SURVIVAL, WrappedChatComponent.fromText(""));
        packet.getPlayerInfoDataLists().write(0, Collections.singletonList(data));
        return packet;
    }

    private PacketContainer createNamedEntitySpawn(int entityId, UUID uuid, Location loc) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.NAMED_ENTITY_SPAWN);
        packet.getIntegers().write(0, entityId);
        packet.getUUIDs().write(0, uuid);
        packet.getDoubles().write(0, loc.getX());
        packet.getDoubles().write(1, loc.getY());
        packet.getDoubles().write(2, loc.getZ());
        packet.getBytes().write(0, (byte)((int)(loc.getYaw()*256.0F/360.0F)));
        packet.getBytes().write(1, (byte)((int)(loc.getPitch()*256.0F/360.0F)));
        return packet;
    }

    private static class FakeInfo {
        UUID uuid; String name; int entityId; Location location; int taskId=-1;
        FakeInfo(UUID u, String n, int id, Location l) { uuid=u; name=n; entityId=id; location=l; }
    }
}
