package com.logisticscraft.lc2.compat;

import com.logisticscraft.lc2.blockentity.AbstractLogisticsStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import mcp.mobius.waila.api.ICommonRegistrar;
import mcp.mobius.waila.api.IDataProvider;
import mcp.mobius.waila.api.IDataWriter;
import mcp.mobius.waila.api.IPluginConfig;
import mcp.mobius.waila.api.IServerAccessor;
import mcp.mobius.waila.api.IWailaCommonPlugin;

/**
 * Shared NeoForge WTHIT common plugin syncing Communication Base Station, Smart Port,
 * and Cargo Station telemetry across 1.21.1, 26.1.2, and 26.2.
 */
public final class Lc2WthitCommonPlugin implements IWailaCommonPlugin {

    @Override
    public void register(ICommonRegistrar registrar) {
        registrar.blockData(CommBaseStationDataProvider.INSTANCE, CommBaseStationBlockEntity.class);
        registrar.blockData(StationDataProvider.INSTANCE, AbstractLogisticsStationBlockEntity.class);
    }

    private enum CommBaseStationDataProvider implements IDataProvider<CommBaseStationBlockEntity> {
        INSTANCE;

        @Override
        public void appendData(
                IDataWriter data,
                IServerAccessor<CommBaseStationBlockEntity> accessor,
                IPluginConfig config
        ) {
            CommBaseStationBlockEntity comm = accessor.getTarget();
            if (comm != null && accessor.getLevel() != null) {
                Lc2ProbeHelper.writeCommStationData(data.raw(), accessor.getLevel(), comm);
            }
        }
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
            if (station != null && accessor.getLevel() != null) {
                Lc2ProbeHelper.writeStationData(data.raw(), accessor.getLevel(), station);
            }
        }
    }
}
