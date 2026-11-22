package com.logisticscraft.lc2.qa;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Lightweight QA-only Forge {@link SimpleChannel} coordinating the real Forge 1.19.2
 * graphical client and dedicated server during {@code runFieldTest} without cluttering HUD chat.
 */
final class Lc2FieldNetwork {

    private static final String PROTOCOL = "1";
    private static SimpleChannel channel;

    private Lc2FieldNetwork() {
    }

    static synchronized void register() {
        if (channel != null) {
            return;
        }
        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(Lc2QaMod.MOD_ID, "field_ctrl"),
                () -> PROTOCOL,
                PROTOCOL::equals,
                PROTOCOL::equals
        );
        int id = 0;
        channel.messageBuilder(S2CFieldDirective.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CFieldDirective::encode)
                .decoder(S2CFieldDirective::decode)
                .consumerMainThread(S2CFieldDirective::handle)
                .add();
        channel.messageBuilder(C2SFieldAck.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SFieldAck::encode)
                .decoder(C2SFieldAck::decode)
                .consumerMainThread(C2SFieldAck::handle)
                .add();
    }

    static void sendToClient(ServerPlayer player, S2CFieldDirective packet) {
        if (channel != null && player != null) {
            channel.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    static void sendToServer(C2SFieldAck packet) {
        if (channel != null) {
            channel.sendToServer(packet);
        }
    }

    record S2CFieldDirective(
            int stepId,
            String action,
            BlockPos targetPos,
            int entityId,
            String argText
    ) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(stepId);
            buf.writeUtf(action == null ? "" : action, 64);
            buf.writeBlockPos(targetPos == null ? BlockPos.ZERO : targetPos);
            buf.writeVarInt(entityId);
            buf.writeUtf(argText == null ? "" : argText, 256);
        }

        static S2CFieldDirective decode(FriendlyByteBuf buf) {
            int step = buf.readVarInt();
            String act = buf.readUtf(64);
            BlockPos pos = buf.readBlockPos();
            int entId = buf.readVarInt();
            String arg = buf.readUtf(256);
            return new S2CFieldDirective(step, act, pos, entId, arg);
        }

        static void handle(S2CFieldDirective pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> Lc2FieldClientDriver.onDirective(pkt)
            ));
            ctx.setPacketHandled(true);
        }
    }

    record C2SFieldAck(
            int stepId,
            boolean success,
            String summary
    ) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(stepId);
            buf.writeBoolean(success);
            buf.writeUtf(summary == null ? "" : summary, 512);
        }

        static C2SFieldAck decode(FriendlyByteBuf buf) {
            int step = buf.readVarInt();
            boolean ok = buf.readBoolean();
            String msg = buf.readUtf(512);
            return new C2SFieldAck(step, ok, msg);
        }

        static void handle(C2SFieldAck pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> Lc2FieldServerRunner.onClientAck(pkt));
            ctx.setPacketHandled(true);
        }
    }
}
