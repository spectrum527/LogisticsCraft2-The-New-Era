package com.logisticscraft.lc2.forge.compat;

import com.logisticscraft.lc2.forge.block.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.forge.block.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import java.util.function.Function;
import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoProvider;
import mcjty.theoneprobe.api.ITheOneProbe;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;

/**
 * Optional soft integration with The One Probe ({@code theoneprobe}) on Forge 1.19.2.
 * Displays Communication Base Station online/offline status, EU/FE energy, and linked adjacent stations.
 */
public final class Lc2TopCompat {

    private static final String TOP_MOD_ID = "theoneprobe";

    private Lc2TopCompat() {
    }

    /** Sends the {@code getTheOneProbe} IMC callback strictly when The One Probe is loaded. */
    public static void onInterModEnqueue(InterModEnqueueEvent event) {
        if (ModList.get().isLoaded(TOP_MOD_ID)) {
            InterModComms.sendTo(TOP_MOD_ID, "getTheOneProbe", TopBridge::create);
        }
    }

    private static final class TopBridge implements Function<ITheOneProbe, Void> {

        private static final ResourceLocation PROVIDER_ID = Lc2Coords.id("comm_base_station");

        private static Object create() {
            return new TopBridge();
        }

        @Override
        public Void apply(ITheOneProbe probe) {
            probe.registerProvider(new IProbeInfoProvider() {
                @Override
                public ResourceLocation getID() {
                    return PROVIDER_ID;
                }

                @Override
                public void addProbeInfo(
                        ProbeMode mode,
                        IProbeInfo probeInfo,
                        Player player,
                        Level level,
                        BlockState blockState,
                        IProbeHitData data
                ) {
                    if (level instanceof ServerLevel serverLevel) {
                        var be = level.getBlockEntity(data.getPos());
                        if (be instanceof CommBaseStationBlockEntity comm) {
                            for (Component line : CommProbeHelper.buildProbeLines(serverLevel, comm)) {
                                probeInfo.mcText(line);
                            }
                        } else if (be instanceof AbstractLogisticsStationBlockEntity station) {
                            for (Component line : CommProbeHelper.buildStationProbeLines(serverLevel, station)) {
                                probeInfo.mcText(line);
                            }
                        }
                    }
                }
            });
            return null;
        }
    }
}
