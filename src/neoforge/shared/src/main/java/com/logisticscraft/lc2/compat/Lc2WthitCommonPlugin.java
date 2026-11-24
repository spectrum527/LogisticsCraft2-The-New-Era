package com.logisticscraft.lc2.compat;

import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import mcp.mobius.waila.api.ICommonRegistrar;
import mcp.mobius.waila.api.IDataProvider;
import mcp.mobius.waila.api.IDataWriter;
import mcp.mobius.waila.api.IPluginConfig;
import mcp.mobius.waila.api.IServerAccessor;
import mcp.mobius.waila.api.IWailaCommonPlugin;

/**
 * Shared NeoForge WTHIT common plugin syncing Communication Base Station telemetry
 * across 1.21.1, 26.1.2, and 26.2.
 */
public final class Lc2WthitCommonPlugin implements IWailaCommonPlugin {

    @Override
    public void register(ICommonRegistrar registrar) {
        registrar.blockData(CommBaseStationDataProvider.INSTANCE, CommBaseStationBlockEntity.class);
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
}
