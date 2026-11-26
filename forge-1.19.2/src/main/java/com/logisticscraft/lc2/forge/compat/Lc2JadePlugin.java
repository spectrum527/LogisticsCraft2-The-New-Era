package com.logisticscraft.lc2.forge.compat;

import com.logisticscraft.lc2.forge.block.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.forge.block.CargoStationBlock;
import com.logisticscraft.lc2.forge.block.CommBaseStationBlock;
import com.logisticscraft.lc2.forge.block.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.forge.block.SmartPortBlock;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Optional soft integration with Jade ({@code jade}) on Forge 1.19.2.
 * Loaded exclusively by Jade's {@link WailaPlugin} scanner when Jade is installed.
 */
@WailaPlugin
public final class Lc2JadePlugin implements IWailaPlugin {

    private static final ResourceLocation COMM_UID = Lc2Coords.id("comm_base_station");
    private static final ResourceLocation STATION_UID = Lc2Coords.id("station");

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(CommStationJadeProvider.INSTANCE, CommBaseStationBlockEntity.class);
        registration.registerBlockDataProvider(LogisticsStationJadeProvider.INSTANCE, AbstractLogisticsStationBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(CommStationJadeProvider.INSTANCE, CommBaseStationBlock.class);
        registration.registerBlockComponent(LogisticsStationJadeProvider.INSTANCE, SmartPortBlock.class);
        registration.registerBlockComponent(LogisticsStationJadeProvider.INSTANCE, CargoStationBlock.class);
    }

    private enum LogisticsStationJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockEntity> {
        INSTANCE;

        @Override
        public ResourceLocation getUid() {
            return STATION_UID;
        }

        @Override
        public void appendServerData(
                CompoundTag data,
                ServerPlayer player,
                Level level,
                BlockEntity blockEntity,
                boolean showDetails
        ) {
            if (blockEntity instanceof AbstractLogisticsStationBlockEntity station) {
                CommProbeHelper.writeStationProbeData(data, station);
            }
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (CommProbeHelper.hasStationProbeData(data)) {
                for (Component line : CommProbeHelper.buildStationProbeLines(data)) {
                    tooltip.add(line);
                }
            }
        }
    }

    private enum CommStationJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockEntity> {
        INSTANCE;

        @Override
        public ResourceLocation getUid() {
            return COMM_UID;
        }

        @Override
        public void appendServerData(
                CompoundTag data,
                ServerPlayer player,
                Level level,
                BlockEntity blockEntity,
                boolean showDetails
        ) {
            if (level instanceof ServerLevel serverLevel && blockEntity instanceof CommBaseStationBlockEntity comm) {
                CommProbeHelper.writeProbeData(data, serverLevel, comm);
            }
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (CommProbeHelper.hasProbeData(data)) {
                for (Component line : CommProbeHelper.buildProbeLines(data)) {
                    tooltip.add(line);
                }
            }
        }
    }
}
