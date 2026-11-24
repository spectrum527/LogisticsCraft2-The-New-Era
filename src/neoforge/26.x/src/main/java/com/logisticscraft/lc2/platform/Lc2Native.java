package com.logisticscraft.lc2.platform;

import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.EnergyUnits;
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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
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
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * NeoForge 26.x (26.1.2 & 26.2) version-specific native API shims.
 */
public final class Lc2Native {

    private Lc2Native() {
    }

    /** Returns the canonical qualified registry ID of the given stack's item. */
    public static String itemId(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }

    /** Looks up a registered item by qualified ID. */
    public static Optional<Item> findItem(String qualifiedId) {
        if (qualifiedId == null || qualifiedId.isEmpty()) {
            return Optional.empty();
        }
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(qualifiedId));
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
        return level.dimension().identifier().toString();
    }

    /** Returns {@code true} if the level is client-side. */
    public static boolean isClientSide(Level level) {
        return level.isClientSide();
    }

    /** Sends an actionbar (overlay) message to a player. */
    public static void sendActionBar(Player player, Component message) {
        player.sendOverlayMessage(message);
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

    /** Registers a block on NeoForge 26.x (attaching ResourceKey ID automatically). */
    public static <T extends Block> DeferredBlock<T> registerBlock(
            DeferredRegister.Blocks blocks,
            String path,
            Function<Block.Properties, T> factory,
            Supplier<Block.Properties> properties
    ) {
        return blocks.registerBlock(path, factory, properties);
    }

    /** Registers an item on NeoForge 26.x (attaching ResourceKey ID automatically). */
    public static <T extends Item> DeferredItem<T> registerItem(
            DeferredRegister.Items items,
            String path,
            Function<Item.Properties, T> factory,
            Supplier<Item.Properties> properties
    ) {
        return items.registerItem(path, factory, properties);
    }

    /** Registers a block item with localized tooltip lines on NeoForge 26.x. */
    public static DeferredItem<Lc2TooltipBlockItem> registerBlockItem(
            DeferredRegister.Items items,
            String path,
            DeferredBlock<? extends Block> block,
            List<String> tooltipKeys
    ) {
        return items.registerItem(
                path,
                props -> new Lc2TooltipBlockItem(block.get(), props.useBlockDescriptionPrefix(), tooltipKeys),
                Item.Properties::new
        );
    }

    /** Creates a BlockEntityType on NeoForge 26.x. */
    public static <T extends BlockEntity> BlockEntityType<T> createBlockEntityType(
            BlockEntityType.BlockEntitySupplier<T> factory,
            Block... blocks
    ) {
        return new BlockEntityType<>(factory, blocks);
    }

    /** Creates an EntityType on NeoForge 26.x. */
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
                .build(ResourceKey.create(
                        Registries.ENTITY_TYPE,
                        Identifier.fromNamespaceAndPath(LogisticsConstants.MOD_ID, path)
                ));
    }

    /** Creates a CustomPacketPayload.Type for an lc2 packet path. */
    public static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(LogisticsConstants.MOD_ID, path)
        );
    }

    /** Registers all C2S and S2C payloads on NeoForge 26.x (split client handler registration). */
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
                    registrar.playToClient(type, codec);
                }
            });
        });
        if (side == Dist.CLIENT) {
            ClientBridge.registerClientHandlers(modBus, registrarCallback);
        }
    }

    /** Sends a payload from client to server. */
    public static void sendToServer(CustomPacketPayload payload) {
        ClientBridge.sendToServer(payload);
    }

    /** Sends a payload from server to a single player. */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** Registers sided item transfer and transactional FE energy capabilities on NeoForge 26.x. */
    public static void registerCapabilities(
            IEventBus modBus,
            Supplier<BlockEntityType<SmartPortBlockEntity>> smartPortType,
            Supplier<BlockEntityType<CargoStationBlockEntity>> cargoStationType,
            Supplier<BlockEntityType<CommBaseStationBlockEntity>> commBaseStationType,
            Supplier<OperationTerminalItem> operationTerminalItem
    ) {
        modBus.addListener(RegisterCapabilitiesEvent.class, event -> {
            event.registerBlockEntity(
                    Capabilities.Item.BLOCK,
                    smartPortType.get(),
                    (be, side) -> new WorldlyContainerWrapper(be, side == null ? Direction.UP : side)
            );
            event.registerBlockEntity(
                    Capabilities.Item.BLOCK,
                    cargoStationType.get(),
                    (be, side) -> new WorldlyContainerWrapper(be, side == null ? Direction.UP : side)
            );
            event.registerBlockEntity(
                    Capabilities.Energy.BLOCK,
                    commBaseStationType.get(),
                    (be, side) -> be.getOrCreatePlatformEnergyHandler(() -> new CommBaseEnergyHandler(be))
            );
            OperationTerminalItem terminal = operationTerminalItem.get();
            event.registerItem(
                    Capabilities.Energy.ITEM,
                    (stack, itemAccess) -> new TerminalEnergyHandler(itemAccess, terminal),
                    terminal
            );
        });
    }

    /** Returns {@code true} if entities in chunk {@code (chunkX, chunkZ)} are loaded and ticking. */
    public static boolean isChunkEntityTicking(ServerLevel level, int chunkX, int chunkZ) {
        return level.areEntitiesActuallyLoadedAndTicking(new ChunkPos(chunkX, chunkZ));
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
            entity.spawnAtLocation(level, stack);
        }
    }

    /** Returns the minimum build Y coordinate of the level. */
    public static int minBuildY(Level level) {
        return level.getMinY();
    }

    /** Returns the maximum build Y coordinate of the level. */
    public static int maxBuildY(Level level) {
        return level.getMaxY();
    }

    /** Reads a block state from a ChunkAccess. */
    public static BlockState getBlockStateFromChunk(ChunkAccess chunk, BlockPos pos) {
        return chunk.getBlockState(pos);
    }

    /** Marks a server-driven entity as needing an immediate client transform/motion sync. */
    public static void markEntityMoved(Entity entity) {
        entity.needsSync = true;
    }

    private static final class CommBaseEnergyHandler extends SnapshotJournal<Double> implements EnergyHandler {
        private final CommBaseStationBlockEntity blockEntity;

        private CommBaseEnergyHandler(CommBaseStationBlockEntity blockEntity) {
            this.blockEntity = blockEntity;
        }

        @Override
        public long getAmountAsLong() {
            return EnergyUnits.euToFeFloor(blockEntity.getStoredEu());
        }

        @Override
        public long getCapacityAsLong() {
            return EnergyUnits.euToFeFloor(LogisticsConstants.COMM_BASE_CAPACITY_EU);
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            if (amount == 0 || blockEntity.isRemoved()) {
                return 0;
            }
            EnergyUnits.FeTransferResult result = EnergyUnits.receiveFeIntoEuBuffer(
                    blockEntity.getStoredEu(),
                    LogisticsConstants.COMM_BASE_CAPACITY_EU,
                    LogisticsConstants.COMM_BASE_PACKET_LIMIT_EU,
                    amount,
                    false
            );
            if (result.acceptedFe() <= 0) {
                return 0;
            }
            updateSnapshots(transaction);
            blockEntity.setStoredEuFromTransaction(result.newStoredEu());
            return result.acceptedFe();
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            return 0;
        }

        @Override
        protected Double createSnapshot() {
            return blockEntity.getStoredEu();
        }

        @Override
        protected void revertToSnapshot(Double snapshot) {
            blockEntity.setStoredEuFromTransaction(snapshot);
        }

        @Override
        protected void onRootCommit(Double originalState) {
            blockEntity.markEnergyChanged();
        }
    }

    private static final class TerminalEnergyHandler implements EnergyHandler {
        private final ItemAccess itemAccess;
        private final OperationTerminalItem terminalItem;

        private TerminalEnergyHandler(ItemAccess itemAccess, OperationTerminalItem terminalItem) {
            this.itemAccess = itemAccess;
            this.terminalItem = terminalItem;
        }

        private boolean isValid() {
            ItemResource res = itemAccess.getResource();
            return res.is(terminalItem) && itemAccess.getAmount() == 1;
        }

        private static double readEu(ItemResource res) {
            CustomData data = res.getComponents().get(DataComponents.CUSTOM_DATA);
            if (data == null || data.isEmpty()) {
                return 0.0;
            }
            double eu = Lc2Nbt.getDouble(data.copyTag(), OperationTerminalItem.KEY_EU, 0.0);
            return Double.isFinite(eu)
                    ? Math.clamp(eu, 0.0, LogisticsConstants.TERMINAL_CAPACITY_EU)
                    : 0.0;
        }

        @Override
        public long getAmountAsLong() {
            ItemResource res = itemAccess.getResource();
            if (!res.is(terminalItem) || itemAccess.getAmount() != 1) {
                return 0L;
            }
            return EnergyUnits.euToFeFloor(readEu(res));
        }

        @Override
        public long getCapacityAsLong() {
            return isValid() ? EnergyUnits.euToFeFloor(LogisticsConstants.TERMINAL_CAPACITY_EU) : 0L;
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            if (amount == 0) {
                return 0;
            }
            ItemResource res = itemAccess.getResource();
            if (!res.is(terminalItem) || itemAccess.getAmount() != 1) {
                return 0;
            }
            double currentEu = readEu(res);
            EnergyUnits.FeTransferResult result = EnergyUnits.receiveFeIntoEuBuffer(
                    currentEu,
                    LogisticsConstants.TERMINAL_CAPACITY_EU,
                    LogisticsConstants.TERMINAL_PACKET_LIMIT_EU,
                    amount,
                    false
            );
            if (result.acceptedFe() <= 0) {
                return 0;
            }
            CustomData existing = res.getComponents().get(DataComponents.CUSTOM_DATA);
            CompoundTag tag = existing == null ? new CompoundTag() : existing.copyTag();
            tag.putDouble(OperationTerminalItem.KEY_EU, result.newStoredEu());
            ItemResource updated = res.with(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            int exchanged = itemAccess.exchange(updated, 1, transaction);
            return exchanged == 1 ? result.acceptedFe() : 0;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            return 0;
        }
    }

    private static final class ClientBridge {
        private static void registerClientHandlers(
                IEventBus modBus,
                Consumer<PayloadBinder> registrarCallback
        ) {
            modBus.addListener(
                    RegisterClientPayloadHandlersEvent.class,
                    event -> registrarCallback.accept(new PayloadBinder() {
                        @Override
                        public <T extends CustomPacketPayload> void playToServer(
                                CustomPacketPayload.Type<T> type,
                                StreamCodec<RegistryFriendlyByteBuf, T> codec,
                                IPayloadHandler<T> handler
                        ) {
                            // Server-bound payloads have no client handler.
                        }

                        @Override
                        public <T extends CustomPacketPayload> void playToClient(
                                CustomPacketPayload.Type<T> type,
                                StreamCodec<RegistryFriendlyByteBuf, T> codec,
                                IPayloadHandler<T> handler
                        ) {
                            event.register(type, HandlerThread.MAIN, handler);
                        }
                    })
            );
        }

        private static void sendToServer(CustomPacketPayload payload) {
            ClientPacketDistributor.sendToServer(payload);
        }
    }
}
