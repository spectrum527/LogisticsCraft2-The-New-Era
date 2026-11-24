package com.logisticscraft.lc2.compat;

import com.logisticscraft.lc2.block.CommBaseStationBlock;
import mcp.mobius.waila.api.IBlockAccessor;
import mcp.mobius.waila.api.IBlockComponentProvider;
import mcp.mobius.waila.api.IClientRegistrar;
import mcp.mobius.waila.api.IPluginConfig;
import mcp.mobius.waila.api.ITooltip;
import mcp.mobius.waila.api.IWailaClientPlugin;
import net.minecraft.network.chat.Component;

/**
 * Shared NeoForge WTHIT client plugin rendering Communication Base Station status, EU/FE,
 * and adjacent logistics station lines across 1.21.1, 26.1.2, and 26.2.
 */
public final class Lc2WthitClientPlugin implements IWailaClientPlugin {

    @Override
    public void register(IClientRegistrar registrar) {
        registrar.body(CommBaseStationComponentProvider.INSTANCE, CommBaseStationBlock.class);
    }

    private enum CommBaseStationComponentProvider implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendBody(ITooltip tooltip, IBlockAccessor accessor, IPluginConfig config) {
            for (Component line : Lc2ProbeHelper.readCommStationLines(accessor.getData().raw())) {
                tooltip.addLine(line);
            }
        }
    }
}
