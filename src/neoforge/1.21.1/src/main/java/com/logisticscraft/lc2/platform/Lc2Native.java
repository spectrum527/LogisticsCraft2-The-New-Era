package com.logisticscraft.lc2.platform;

import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.item.OperationTerminalItem;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
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
import net.neoforged.neoforge.energy.IEnergyStorage;
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

    /** Common NeoForge wrench item tag ({@code #c:tools/wrench}). */
    public static final TagKey<Item> TAG_C_TOOLS_WRENCH = itemTag("c", "tools/wrench");

    /** Plural fallback wrench item tag ({@code #c:tools/wrenches}). */
    public static final TagKey<Item> TAG_C_TOOLS_WRENCHES = itemTag("c", "tools/wrenches");

    /** Legacy/alias common wrench item tag ({@code #c:wrenches}). */
    public static final TagKey<Item> TAG_C_WRENCHES = itemTag("c", "wrenches");

    /** Optional LC2 item tag extending the single-use battery slot ({@code #lc2:single_use_batteries}). */
    public static final TagKey<Item> TAG_SINGLE_USE_BATTERIES =
            itemTag(LogisticsConstants.MOD_ID, "single_use_batteries");

    /** Common relocation-protection block tag ({@code #c:relocation_not_supported}). */
    public static final TagKey<Block> TAG_C_RELOCATION_NOT_SUPPORTED =
            blockTag("c", "relocation_not_supported");

    private Lc2Native() {
    }

    /** Creates an item {@link TagKey} for the given namespace and path. */
    public static TagKey<Item> itemTag(String namespace, String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    /** Creates a block {@link TagKey} for the given namespace and path. */
    public static TagKey<Block> blockTag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    /** Returns {@code true} if {@code stack} is a supported single-use battery item. */
    public static boolean isSingleUseBattery(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.is(TAG_SINGLE_USE_BATTERIES)
                || LogisticsConstants.SINGLE_USE_BATTERY_ID.equals(itemId(stack));
    }

    /**
     * Returns {@code true} if {@code stack} is a third-party wrench ({@code #c:tools/wrench},
     * {@code #c:tools/wrenches}, or {@code #c:wrenches}), excluding IC2's own wrench items so
     * IC2's {@code WrenchTarget} never double-triggers.
     */
    public static boolean isThirdPartyWrench(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = itemId(stack);
        if (id.startsWith(LogisticsConstants.IC2_MOD_ID + ":")) {
            return false;
        }
        return stack.is(TAG_C_TOOLS_WRENCH)
                || stack.is(TAG_C_TOOLS_WRENCHES)
                || stack.is(TAG_C_WRENCHES);
    }

    /** Damages a damageable wrench item by 1 point when used in survival mode. */
    public static void damageWrenchItem(ItemStack stack, Player player, InteractionHand hand) {
        if (player != null && !player.isCreative() && stack.isDamageableItem()) {
            EquipmentSlot slot = hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
            stack.hurtAndBreak(1, player, slot);
        }
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

    /** Returns a mutable copy of the stack's {@code DataComponents.CUSTOM_DATA} tag (empty if absent). */
    public static CompoundTag copyCustomData(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    /** Updates the stack's {@code DataComponents.CUSTOM_DATA} tag in place. */
    public static void updateCustomData(ItemStack stack, Consumer<CompoundTag> updater) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, updater);
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

    /** Registers all C2S and S2C payloads on NeoForge 1.21.1. */
    public static void registerPayloads(
            IEventBus modBus,
            Dist side,
            String protocolVersion,
            Consumer<PayloadBinder> registrarCallback
    ) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            var registrar = event.registrar(protocolVersion).executesOn(HandlerThread.MAIN);
            registrarCallback.accept(new PayloadBinder() {
                @Override
                public <T extends CustomPacketPayload> void playToServer(
                        CustomPacketPayload.Type<T> type,
                        StreamCodec<RegistryFriendlyByteBuf, T> codec,
                        IPayloadHandler<T> handler
                ) {
                    registrar.playToServer(type, codec, handler);
                }

                @Override
                public <T extends CustomPacketPayload> void playToClient(
                        CustomPacketPayload.Type<T> type,
                        StreamCodec<RegistryFriendlyByteBuf, T> codec,
                        IPayloadHandler<T> handler
                ) {
                    registrar.playToClient(type, codec, handler);
                }
            });
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

    /** Registers sided item handler and FE energy capabilities on NeoForge 1.21.1. */
    public static void registerCapabilities(
            IEventBus modBus,
            Supplier<BlockEntityType<SmartPortBlockEntity>> smartPortType,
            Supplier<BlockEntityType<CargoStationBlockEntity>> cargoStationType,
            Supplier<BlockEntityType<CommBaseStationBlockEntity>> commBaseStationType,
            Supplier<OperationTerminalItem> operationTerminalItem
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
            event.registerBlockEntity(
                    Capabilities.EnergyStorage.BLOCK,
                    commBaseStationType.get(),
                    (be, side) -> new IEnergyStorage() {
                        @Override
                        public int receiveEnergy(int toReceive, boolean simulate) {
                            return be.receiveFe(toReceive, simulate);
                        }

                        @Override
                        public int extractEnergy(int toExtract, boolean simulate) {
                            return 0;
                        }

                        @Override
                        public int getEnergyStored() {
                            return be.getStoredFe();
                        }

                        @Override
                        public int getMaxEnergyStored() {
                            return be.getMaxFe();
                        }

                        @Override
                        public boolean canExtract() {
                            return false;
                        }

                        @Override
                        public boolean canReceive() {
                            return true;
                        }
                    }
            );
            event.registerItem(
                    Capabilities.EnergyStorage.ITEM,
                    (stack, ctx) -> new IEnergyStorage() {
                        @Override
                        public int receiveEnergy(int toReceive, boolean simulate) {
                            return OperationTerminalItem.receiveFe(stack, toReceive, simulate);
                        }

                        @Override
                        public int extractEnergy(int toExtract, boolean simulate) {
                            return 0;
                        }

                        @Override
                        public int getEnergyStored() {
                            return OperationTerminalItem.getStoredFe(stack);
                        }

                        @Override
                        public int getMaxEnergyStored() {
                            return OperationTerminalItem.getMaxFe();
                        }

                        @Override
                        public boolean canExtract() {
                            return false;
                        }

                        @Override
                        public boolean canReceive() {
                            return true;
                        }
                    },
                    operationTerminalItem.get()
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
