package com.logisticscraft.lc2.forge.network;

import com.logisticscraft.lc2.forge.block.AbstractLogisticsStationBlockEntity;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client-to-server control packet for Smart Port and Cargo Station GUI interactions.
 */
public record C2SStationActionPacket(
        BlockPos stationPos,
        Action action,
        String textArg,
        boolean boolArg,
        int xArg,
        int yArg,
        int zArg
) {

    public enum Action {
        SET_NAME,
        SET_TARGET,
        CYCLE_TARGET,
        TOGGLE_AUTO_RETURN,
        TOGGLE_ENERGY_PRIORITY,
        DISPATCH,
        REQUEST_SYNC
    }

    public static C2SStationActionPacket setName(BlockPos pos, String name) {
        return new C2SStationActionPacket(pos, Action.SET_NAME, name == null ? "" : name, false, 0, 0, 0);
    }

    public static C2SStationActionPacket setTarget(BlockPos pos, boolean hasTarget, int x, int y, int z) {
        return new C2SStationActionPacket(pos, Action.SET_TARGET, "", hasTarget, x, y, z);
    }

    public static C2SStationActionPacket cycleTarget(BlockPos pos, int delta) {
        return new C2SStationActionPacket(pos, Action.CYCLE_TARGET, "", false, delta, 0, 0);
    }

    public static C2SStationActionPacket simple(BlockPos pos, Action action) {
        return new C2SStationActionPacket(pos, action, "", false, 0, 0, 0);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(stationPos);
        buf.writeEnum(action);
        buf.writeUtf(textArg, 64);
        buf.writeBoolean(boolArg);
        buf.writeVarInt(xArg);
        buf.writeVarInt(yArg);
        buf.writeVarInt(zArg);
    }

    public static C2SStationActionPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        Action action = buf.readEnum(Action.class);
        String text = buf.readUtf(64);
        boolean flag = buf.readBoolean();
        int x = buf.readVarInt();
        int y = buf.readVarInt();
        int z = buf.readVarInt();
        return new C2SStationActionPacket(pos, action, text, flag, x, y, z);
    }

    public static void handle(C2SStationActionPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.getLevel();
            if (!level.isLoaded(packet.stationPos)) {
                return;
            }
            if (player.distanceToSqr(Vec3.atCenterOf(packet.stationPos)) > 64.0) {
                return;
            }
            BlockEntity be = level.getBlockEntity(packet.stationPos);
            if (!(be instanceof AbstractLogisticsStationBlockEntity station)) {
                return;
            }
            switch (packet.action) {
                case SET_NAME -> station.setStationName(packet.textArg);
                case SET_TARGET -> station.setTargetPos(
                        packet.boolArg ? new BlockPos(packet.xArg, packet.yArg, packet.zArg) : null
                );
                case CYCLE_TARGET -> station.cycleTarget(level, packet.xArg >= 0 ? 1 : -1);
                case TOGGLE_AUTO_RETURN -> station.toggleAutoReturn();
                case TOGGLE_ENERGY_PRIORITY -> station.toggleEnergyPriority();
                case DISPATCH -> station.tryDispatch(level, true);
                case REQUEST_SYNC -> {
                    // Just send updated sync packet below
                }
            }
            station.sendSyncTo(player);
        });
        ctx.setPacketHandled(true);
    }
}
