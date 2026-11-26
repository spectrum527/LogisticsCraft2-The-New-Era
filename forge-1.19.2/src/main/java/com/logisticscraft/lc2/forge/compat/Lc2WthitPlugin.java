package com.logisticscraft.lc2.forge.compat;

import com.logisticscraft.lc2.forge.block.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.forge.block.CargoStationBlock;
import com.logisticscraft.lc2.forge.block.CommBaseStationBlock;
import com.logisticscraft.lc2.forge.block.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.forge.block.SmartPortBlock;
import mcp.mobius.waila.api.IBlockAccessor;
import mcp.mobius.waila.api.IBlockComponentProvider;
import mcp.mobius.waila.api.IClientRegistrar;
import mcp.mobius.waila.api.ICommonRegistrar;
import mcp.mobius.waila.api.IDataProvider;
import mcp.mobius.waila.api.IDataWriter;
import mcp.mobius.waila.api.IPluginConfig;
import mcp.mobius.waila.api.IServerAccessor;
import mcp.mobius.waila.api.ITooltip;
import mcp.mobius.waila.api.IWailaClientPlugin;
import mcp.mobius.waila.api.IWailaCommonPlugin;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * Optional soft integration with WTHIT ({@code wthit}) on Forge 1.19.2, registered via
 * {@code wthit_plugins.json} and loaded exclusively when WTHIT is installed.
 */
public final class Lc2WthitPlugin implements IWailaCommonPlugin, IWailaClientPlugin {

    @Override
    public void register(ICommonRegistrar registrar) {
        registrar.blockData(CommDataProvider.INSTANCE, CommBaseStationBlockEntity.class);
        registrar.blockData(StationDataProvider.INSTANCE, AbstractLogisticsStationBlockEntity.class);
    }

    @Override
    public void register(IClientRegistrar registrar) {
        registrar.body(CommComponentProvider.INSTANCE, CommBaseStationBlock.class);
        registrar.body(StationComponentProvider.INSTANCE, SmartPortBlock.class);
        registrar.body(StationComponentProvider.INSTANCE, CargoStationBlock.class);
    }

    private enum StationDataProvider implements IDataProvider<AbstractLogisticsStationBlockEntity> {
        INSTANCE;

        @Override
        public void appendData(
                IDataWriter data,
                IServerAccessor<AbstractLogisticsStationBlockEntity> accessor,
                IPluginConfig config
        ) {
            AbstractLogisticsStationBlockEntity station = accessor.getTarget();
            if (station != null) {
                CommProbeHelper.writeStationProbeData(data.raw(), station);
            }
        }
    }

    private enum StationComponentProvider implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendBody(ITooltip tooltip, IBlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getData().raw();
            if (CommProbeHelper.hasStationProbeData(data)) {
                for (Component line : CommProbeHelper.buildStationProbeLines(data)) {
                    tooltip.addLine(line);
                }
            }
        }
    }

    private enum CommDataProvider implements IDataProvider<CommBaseStationBlockEntity> {
        INSTANCE;

        @Override
        public void appendData(
                IDataWriter data,
                IServerAccessor<CommBaseStationBlockEntity> accessor,
                IPluginConfig config
        ) {
            CommBaseStationBlockEntity comm = accessor.getTarget();
            if (comm != null) {
                CommProbeHelper.writeProbeData(data.raw(), accessor.getLevel(), comm);
            }
        }
    }

    private enum CommComponentProvider implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendBody(ITooltip tooltip, IBlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getData().raw();
            if (CommProbeHelper.hasProbeData(data)) {
                for (Component line : CommProbeHelper.buildProbeLines(data)) {
                    tooltip.addLine(line);
                }
            }
        }
    }
}
