package com.logisticscraft.lc2.platform;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

/**
 * Version-neutral NeoForge payload registration callback bridging 1.21.1 unified registration
 * and 26.x split common/client handler registration.
 */
public interface PayloadBinder {

    /** Registers a client-to-server play payload with its server-side handler. */
    <T extends CustomPacketPayload> void playToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<RegistryFriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler
    );

    /** Registers a server-to-client play payload with its client-side handler. */
    <T extends CustomPacketPayload> void playToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<RegistryFriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler
    );
}
