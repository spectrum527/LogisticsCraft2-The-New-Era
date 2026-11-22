package com.logisticscraft.lc2.platform;

import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.LogisticsConstants;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * NeoForge 1.21.1 version-specific native API shims.
 */
public final class Lc2Native {

    private Lc2Native() {
    }

    /** Returns the canonical qualified registry ID of the given stack's item. */
    public static String itemId(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }

    /** Looks up a registered item by qualified ID. */
    public static Optional<Item> findItem(String qualifiedId) {
        if (qualifiedId == null || qualifiedId.isEmpty()) {
            return Optional.empty();
        }
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(qualifiedId));
    }

    /** Creates an ItemStack from a qualified item ID and count, or empty if absent. */
    public static ItemStack stack(String qualifiedId, int count) {
        if (count <= 0) {
            return ItemStack.EMPTY;
        }
        return findItem(qualifiedId).map(item -> new ItemStack(item, count)).orElse(ItemStack.EMPTY);
    }

    /** Returns the namespaced dimension ID string of a ServerLevel. */
    public static String dimensionId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    /** Returns {@code true} if the level is client-side. */
    public static boolean isClientSide(Level level) {
        return level.isClientSide;
    }

    /** Sends an actionbar (overlay) message to a player. */
    public static void sendActionBar(Player player, Component message) {
        player.displayClientMessage(message, true);
    }

    /** Registers a block on NeoForge 1.21.1. */
    public static <T extends Block> DeferredBlock<T> registerBlock(
            DeferredRegister.Blocks blocks,
            String path,
            Function<Block.Properties, T> factory,
            Supplier<Block.Properties> properties
    ) {
        return blocks.registerBlock(path, factory, properties.get());
    }

    /** Registers an item on NeoForge 1.21.1. */
    public static <T extends Item> DeferredItem<T> registerItem(
            DeferredRegister.Items items,
            String path,
            Function<Item.Properties, T> factory,
            Supplier<Item.Properties> properties
    ) {
        return items.registerItem(path, factory, properties.get());
    }

    /** Registers a block item with localized tooltip lines on NeoForge 1.21.1. */
    public static DeferredItem<Lc2TooltipBlockItem> registerBlockItem(
            DeferredRegister.Items items,
            String path,
            DeferredBlock<? extends Block> block,
            List<String> tooltipKeys
    ) {
        return items.registerItem(
                path,
                props -> new Lc2TooltipBlockItem(block.get(), props, tooltipKeys),
                new Item.Properties()
        );
    }

    /** Creates a BlockEntityType on NeoForge 1.21.1. */
    public static <T extends BlockEntity> BlockEntityType<T> createBlockEntityType(
            BlockEntityType.BlockEntitySupplier<T> factory,
            Block... blocks
    ) {
        return BlockEntityType.Builder.of(factory, blocks).build(null);
    }

    /** Creates an EntityType on NeoForge 1.21.1. */
    public static <T extends Entity> EntityType<T> createEntityType(
            String path,
            EntityType.EntityFactory<T> factory,
            float width,
            float height,
            int clientTrackingRange,
            int updateInterval
    ) {
        return EntityType.Builder.<T>of(factory, MobCategory.MISC)
                .sized(width, height)
                .clientTrackingRange(clientTrackingRange)
                .updateInterval(updateInterval)
                .build(LogisticsConstants.MOD_ID + ":" + path);
    }

    /** Creates a CustomPacketPayload.Type for an lc2 packet path. */
    public static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(LogisticsConstants.MOD_ID, path)
        );
    }

    /** Registers one C2S and one S2C payload on NeoForge 1.21.1. */
    public static <C extends CustomPacketPayload, S extends CustomPacketPayload> void registerPayloads(
            IEventBus modBus,
            Dist side,
            String protocolVersion,
            CustomPacketPayload.Type<C> c2sType,
            StreamCodec<RegistryFriendlyByteBuf, C> c2sCodec,
            IPayloadHandler<C> c2sHandler,
            CustomPacketPayload.Type<S> s2cType,
            StreamCodec<RegistryFriendlyByteBuf, S> s2cCodec,
            IPayloadHandler<S> s2cHandler
    ) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            var registrar = event.registrar(protocolVersion).executesOn(HandlerThread.MAIN);
            registrar.playToServer(c2sType, c2sCodec, c2sHandler);
            registrar.playToClient(s2cType, s2cCodec, s2cHandler);
        });
    }

    /** Sends a payload from client to server. */
    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /** Sends a payload from server to a single player. */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** Registers sided item handler capabilities for Smart Port and Cargo Station. */
    public static void registerItemCapabilities(
            IEventBus modBus,
            Supplier<BlockEntityType<SmartPortBlockEntity>> smartPortType,
            Supplier<BlockEntityType<CargoStationBlockEntity>> cargoStationType
    ) {
        modBus.addListener(RegisterCapabilitiesEvent.class, event -> {
            event.registerBlockEntity(
                    Capabilities.ItemHandler.BLOCK,
                    smartPortType.get(),
                    (be, side) -> new SidedInvWrapper(be, side == null ? Direction.UP : side)
            );
            event.registerBlockEntity(
                    Capabilities.ItemHandler.BLOCK,
                    cargoStationType.get(),
                    (be, side) -> new SidedInvWrapper(be, side == null ? Direction.UP : side)
            );
        });
    }

    /** Returns {@code true} if entities in chunk {@code (chunkX, chunkZ)} are loaded and ticking. */
    public static boolean isChunkEntityTicking(ServerLevel level, int chunkX, int chunkZ) {
        return level.areEntitiesLoaded(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** Loads a chunk synchronously up to FULL status without adding persistent chunk tickets. */
    public static ChunkAccess loadChunkForProbe(ServerLevel level, int chunkX, int chunkZ) {
        return level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
    }

    /** Returns the loaded chunk if present without triggering chunk generation/loading. */
    public static ChunkAccess getLoadedChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (!level.hasChunk(chunkX, chunkZ)) {
            return null;
        }
        return level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
    }

    /** Spawns an ItemStack drop at the given entity's location. */
    public static void spawnAtLocation(Entity entity, ServerLevel level, ItemStack stack) {
        if (!stack.isEmpty()) {
            entity.spawnAtLocation(stack);
        }
    }

    /** Returns the minimum build Y coordinate of the level. */
    public static int minBuildY(Level level) {
        return level.getMinBuildHeight();
    }

    /** Returns the maximum build Y coordinate of the level. */
    public static int maxBuildY(Level level) {
        return level.getMaxBuildHeight();
    }

    /** Reads a block state from a ChunkAccess. */
    public static BlockState getBlockStateFromChunk(ChunkAccess chunk, BlockPos pos) {
        return chunk.getBlockState(pos);
    }

    /** Marks a server-driven entity as needing an immediate client transform/motion sync. */
    public static void markEntityMoved(Entity entity) {
        entity.hasImpulse = true;
    }
}
