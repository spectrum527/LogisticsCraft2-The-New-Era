package com.logisticscraft.lc2.forge.network;

import com.logisticscraft.lc2.forge.util.Lc2Coords;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Forge 1.19.2 {@link SimpleChannel} networking registry for LogisticsCraft².
 */
public final class Lc2Network {

    private static final String PROTOCOL_VERSION = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            Lc2Coords.id("main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static boolean registered = false;

    private Lc2Network() {
    }

    /** Registers all C2S and S2C packets once during common setup. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        int id = 0;
        CHANNEL.messageBuilder(C2SStationActionPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SStationActionPacket::encode)
                .decoder(C2SStationActionPacket::decode)
                .consumerMainThread(C2SStationActionPacket::handle)
                .add();
        CHANNEL.messageBuilder(S2CStationSyncPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CStationSyncPacket::encode)
                .decoder(S2CStationSyncPacket::decode)
                .consumerMainThread(S2CStationSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(C2STerminalActionPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2STerminalActionPacket::encode)
                .decoder(C2STerminalActionPacket::decode)
                .consumerMainThread(C2STerminalActionPacket::handle)
                .add();
        CHANNEL.messageBuilder(S2CTerminalSyncPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CTerminalSyncPacket::encode)
                .decoder(S2CTerminalSyncPacket::decode)
                .consumerMainThread(S2CTerminalSyncPacket::handle)
                .add();
    }

    /** Sends a C2S packet from client to server. */
    public static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }

    /** Sends an S2C packet to a specific player. */
    public static void sendToPlayer(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
