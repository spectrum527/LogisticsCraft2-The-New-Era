package com.logisticscraft.lc2.compat;

import com.logisticscraft.lc2.block.CommBaseStationBlock;
import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.core.LogisticsConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 * NeoForge 1.21.1 Jade plugin syncing and rendering live Communication Base Station telemetry.
 */
@WailaPlugin
public final class Lc2JadePlugin implements IWailaPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
            LogisticsConstants.MOD_ID,
            "comm_base_station"
    );

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(CommBaseStationProvider.INSTANCE, CommBaseStationBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(CommBaseStationProvider.INSTANCE, CommBaseStationBlock.class);
    }

    private enum CommBaseStationProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public ResourceLocation getUid() {
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
