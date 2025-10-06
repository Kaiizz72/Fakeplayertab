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
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cChỉ player mới dùng được lệnh này!");
            return true;
        }
        Player player = (Player) sender;
        if (cmd.getName().equalsIgnoreCase("fakespawn")) {
            spawnFake(player.getLocation(), "Fake" + (fakes.size() + 1));
            player.sendMessage("§aĐã spawn fake player!");
            return true;
        } else if (cmd.getName().equalsIgnoreCase("fakeremove")) {
            removeFakes();
            player.sendMessage("§cĐã xoá toàn bộ fake players!");
            return true;
        }
        return false;
    }

    private void spawnFake(Location loc, String name) {
        UUID uuid = UUID.randomUUID();
        int entityId = nextEntityId++;

        PacketContainer addPlayer = createPlayerInfoPacket(uuid, name, EnumWrappers.PlayerInfoAction.ADD_PLAYER);
        PacketContainer namedEntity = createNamedEntitySpawn(entityId, uuid, loc);

        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                protocolManager.sendServerPacket(p, addPlayer);
                protocolManager.sendServerPacket(p, namedEntity);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        fakes.put(uuid, new FakeInfo(uuid, name, entityId, loc));
    }

    private void removeFakes() {
        for (FakeInfo info : fakes.values()) {
            PacketContainer removePlayer = createPlayerInfoPacket(info.uuid, info.name, EnumWrappers.PlayerInfoAction.REMOVE_PLAYER);
            try {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    protocolManager.sendServerPacket(p, removePlayer);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        fakes.clear();
    }

    private PacketContainer createPlayerInfoPacket(UUID uuid, String name, EnumWrappers.PlayerInfoAction action) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO);

        // Dùng Set chuẩn (compile OK, runtime OK)
        Set<EnumWrappers.PlayerInfoAction> actions = Collections.singleton(action);
        packet.getPlayerInfoActions().write(0, actions);

        WrappedGameProfile profile = new WrappedGameProfile(uuid, name);
        WrappedChatComponent displayName = WrappedChatComponent.fromText("");
        PlayerInfoData data = new PlayerInfoData(profile, 0,
                EnumWrappers.NativeGameMode.SURVIVAL, displayName);

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
        packet.getBytes().write(0, (byte) ((int) (loc.getYaw() * 256.0F / 360.0F)));
        packet.getBytes().write(1, (byte) ((int) (loc.getPitch() * 256.0F / 360.0F)));
        return packet;
    }

    private static class FakeInfo {
        UUID uuid;
        String name;
        int entityId;
        Location location;
        int taskId = -1;

        FakeInfo(UUID u, String n, int id, Location l) {
            uuid = u;
            name = n;
            entityId = id;
            location = l;
        }
    }
}
