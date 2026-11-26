package com.logisticscraft.lc2.compat;

import com.logisticscraft.lc2.block.CargoStationBlock;
import com.logisticscraft.lc2.block.CommBaseStationBlock;
import com.logisticscraft.lc2.block.SmartPortBlock;
import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.core.LogisticsConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
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
 * NeoForge 26.x Jade plugin syncing and rendering live Communication Base Station telemetry.
 */
@WailaPlugin
public final class Lc2JadePlugin implements IWailaPlugin {

    private static final Identifier UID = Identifier.fromNamespaceAndPath(
            LogisticsConstants.MOD_ID,
            "comm_base_station"
    );
    private static final Identifier STATION_UID = Identifier.fromNamespaceAndPath(
            LogisticsConstants.MOD_ID,
            "station"
    );

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(CommBaseStationProvider.INSTANCE, CommBaseStationBlockEntity.class);
        registration.registerBlockDataProvider(StationProvider.INSTANCE, AbstractLogisticsStationBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(CommBaseStationProvider.INSTANCE, CommBaseStationBlock.class);
        registration.registerBlockComponent(StationProvider.INSTANCE, SmartPortBlock.class);
        registration.registerBlockComponent(StationProvider.INSTANCE, CargoStationBlock.class);
    }

    private enum StationProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public Identifier getUid() {
            return STATION_UID;
        }

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (accessor.getLevel() instanceof ServerLevel serverLevel
                    && accessor.getBlockEntity() instanceof AbstractLogisticsStationBlockEntity station) {
                Lc2ProbeHelper.writeStationData(data, serverLevel, station);
            }
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            for (Component line : Lc2ProbeHelper.readStationLines(accessor.getServerData())) {
                tooltip.add(line);
            }
        }
    }

    private enum CommBaseStationProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public Identifier getUid() {
            return UID;
        }

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (accessor.getLevel() instanceof ServerLevel serverLevel
                    && accessor.getBlockEntity() instanceof CommBaseStationBlockEntity comm) {
                Lc2ProbeHelper.writeCommStationData(data, serverLevel, comm);
            }
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            for (Component line : Lc2ProbeHelper.readCommStationLines(accessor.getServerData())) {
                tooltip.add(line);
            }
        }
    }
}
