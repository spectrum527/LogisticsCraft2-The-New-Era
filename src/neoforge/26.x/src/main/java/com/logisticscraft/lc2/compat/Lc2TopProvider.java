package com.logisticscraft.lc2.compat;

import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.core.LogisticsConstants;
import java.util.function.Function;
import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoProvider;
import mcjty.theoneprobe.api.ITheOneProbe;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * NeoForge 26.x The One Probe provider displaying live Communication Base Station
 * online/offline status, EU/FE energy reading, and adjacent logistics stations.
 */
public final class Lc2TopProvider implements IProbeInfoProvider {

    private static final Identifier ID = Identifier.fromNamespaceAndPath(
            LogisticsConstants.MOD_ID,
            "comm_base_station"
    );

    /** IMC callback supplier consumed by The One Probe when {@code theoneprobe} is loaded. */
    public static Function<ITheOneProbe, Void> createCallback() {
        return probe -> {
            probe.registerProvider(new Lc2TopProvider());
            return null;
        };
    }

    @Override
    public Identifier getID() {
        return ID;
    }

    @Override
    public void addProbeInfo(
            ProbeMode mode,
            IProbeInfo info,
            Player player,
            Level level,
            BlockState state,
            IProbeHitData data
    ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (serverLevel.getBlockEntity(data.getPos()) instanceof CommBaseStationBlockEntity comm) {
            for (Component line : Lc2ProbeHelper.buildCommStationLines(
                    comm.isOnline(),
                    comm.getStoredEu(),
                    comm.getMaxEu(),
                    comm.formatAdjacentComponent(serverLevel)
            )) {
                info.mcText(line);
            }
        } else if (serverLevel.getBlockEntity(data.getPos()) instanceof AbstractLogisticsStationBlockEntity station) {
            for (Component line : Lc2ProbeHelper.buildStationLines(serverLevel, station)) {
                info.mcText(line);
            }
        }
    }
}
