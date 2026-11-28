package com.logisticscraft.lc2.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * NeoForge 26.x (26.1.2 & 26.2) version-specific QA helpers.
 */
public final class QaPlatform {

    private QaPlatform() {
    }

    /** Returns all registered recipe IDs in the live RecipeManager as qualified strings. */
    public static Set<String> allRecipeIds(MinecraftServer server) {
        Set<String> ids = new TreeSet<>();
        server.getRecipeManager().getRecipes()
                .forEach(holder -> ids.add(holder.id().identifier().toString()));
        return ids;
    }

    /** Invokes live entity interaction on 26.x. */
    public static InteractionResult interactEntity(Entity entity, Player player) {
        return entity.interact(player, InteractionHand.MAIN_HAND, Vec3.ZERO);
    }

    /** Spawns a fresh Villager passenger at the given world position. */
    public static Mob spawnPassenger(ServerLevel level, double x, double y, double z) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE
                .getOptional(Identifier.parse("minecraft:villager"))
                .orElseThrow(() -> new AssertionError("Missing minecraft:villager entity type"));
        Entity created = type.create(level, EntitySpawnReason.COMMAND);
        if (!(created instanceof Mob mob)) {
            throw new AssertionError("Failed to create Villager passenger: " + created);
        }
        mob.setPos(x, y, z);
        mob.setNoAi(true);
        if (!level.addFreshEntity(mob)) {
            throw new AssertionError("Failed to add Villager passenger to level");
        }
        return mob;
    }

    /** Teleports a player to exact coordinates and view angles on 26.x. */
    public static void teleportPlayer(
            ServerPlayer player,
            ServerLevel level,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        player.stopRiding();
        player.teleportTo(level, x, y, z, Set.of(), yaw, pitch, false);
    }

    /** Returns the world spawn position. */
    public static BlockPos spawnPos(ServerLevel level) {
        return level.getRespawnData().pos();
    }

    /** Returns the qualified biome ID at {@code pos}. */
    public static String biomeId(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("unknown");
    }

    /** Reads the block's vanilla comparator analog output signal (0..15). */
    public static int analogOutputSignal(ServerLevel level, BlockPos pos, Direction side) {
        return level.getBlockState(pos).getAnalogOutputSignal(level, pos, side);
    }

    /** Non-destructively reads all non-empty ItemStacks stored in the container/capability at {@code pos}. */
    public static List<ItemStack> readAllStacks(ServerLevel level, BlockPos pos) {
        List<ItemStack> result = new ArrayList<>();
        var handler = level.getCapability(Capabilities.Item.BLOCK, pos, Direction.UP);
        if (handler != null) {
            for (int i = 0; i < handler.size(); i++) {
                int amt = handler.getAmountAsInt(i);
                if (amt > 0) {
                    ItemStack s = handler.getResource(i).toStack(amt);
                    if (!s.isEmpty()) {
                        result.add(s);
                    }
                }
            }
            return result;
        }
        BlockState state = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);
        Container container = null;
        if (state.getBlock() instanceof ChestBlock chest) {
            container = ChestBlock.getContainer(chest, state, level, pos, true);
        } else if (be instanceof Container c) {
            container = c;
        }
        if (container != null) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (!s.isEmpty()) {
                    result.add(s.copy());
                }
            }
        }
        return result;
    }

    /** Loads a legacy v1 station NBT payload (without {@code layoutVersion}) into {@code be} on 26.x. */
    public static void loadLegacyV1Station(
            ServerLevel level,
            BlockEntity be,
            List<ItemStack> legacyItems,
            CompoundTag legacyLc2Tag
    ) {
        CompoundTag root = new CompoundTag();
        var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var encodedItems = ItemStack.OPTIONAL_CODEC.listOf()
                .encodeStart(ops, legacyItems)
                .getOrThrow();
        root.put("items", encodedItems);
        root.put("trera", legacyLc2Tag.copy());
        be.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), root));
    }

    /** Returns {@code true} if the block at {@code pos} exposes a 26.x transactional FE block capability. */
    public static boolean hasBlockFeCapability(ServerLevel level, BlockPos pos, Direction side) {
        return level.getCapability(Capabilities.Energy.BLOCK, pos, side) != null;
    }

    /** Reads the FE energy stored in the block capability at {@code pos} on {@code side}. */
    public static int blockFeStored(ServerLevel level, BlockPos pos, Direction side) {
        EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, pos, side);
        if (handler == null) {
            throw new AssertionError("Missing FE block capability at " + pos + " side=" + side);
        }
        return (int) handler.getAmountAsLong();
    }

    /** Reads the FE capacity of the block capability at {@code pos} on {@code side}. */
    public static int blockFeCapacity(ServerLevel level, BlockPos pos, Direction side) {
        EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, pos, side);
        if (handler == null) {
            throw new AssertionError("Missing FE block capability at " + pos + " side=" + side);
        }
        return (int) handler.getCapacityAsLong();
    }

    /** Inserts FE into the 26.x block capability using a root {@link Transaction} (rolled back if simulated). */
    public static int blockFeReceive(ServerLevel level, BlockPos pos, Direction side, int offeredFe, boolean simulate) {
        EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, pos, side);
        if (handler == null) {
            throw new AssertionError("Missing FE block capability at " + pos + " side=" + side);
        }
        try (var tx = Transaction.openRoot()) {
            int accepted = handler.insert(offeredFe, tx);
            if (!simulate) {
                tx.commit();
            }
            return accepted;
        }
    }

    /** Attempts to extract FE from the 26.x block capability using a root {@link Transaction}. */
    public static int blockFeExtract(ServerLevel level, BlockPos pos, Direction side, int maxExtract, boolean simulate) {
        EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, pos, side);
        if (handler == null) {
            throw new AssertionError("Missing FE block capability at " + pos + " side=" + side);
        }
        try (var tx = Transaction.openRoot()) {
            int extracted = handler.extract(maxExtract, tx);
            if (!simulate) {
                tx.commit();
            }
            return extracted;
        }
    }

    /** Returns {@code true} if the item in {@code player}'s inventory slot {@code invSlot} exposes FE capability. */
    public static boolean hasItemFeCapability(Player player, int invSlot) {
        return ItemAccess.forPlayerSlot(player, invSlot).getCapability(Capabilities.Energy.ITEM) != null;
    }

    /** Reads the FE energy stored in the item capability in {@code player}'s inventory slot {@code invSlot}. */
    public static int itemFeStored(Player player, int invSlot) {
        EnergyHandler handler = ItemAccess.forPlayerSlot(player, invSlot).getCapability(Capabilities.Energy.ITEM);
        if (handler == null) {
            throw new AssertionError("Missing FE item capability in player slot " + invSlot);
        }
        return (int) handler.getAmountAsLong();
    }

    /** Reads the FE capacity of the item capability in {@code player}'s inventory slot {@code invSlot}. */
    public static int itemFeCapacity(Player player, int invSlot) {
        EnergyHandler handler = ItemAccess.forPlayerSlot(player, invSlot).getCapability(Capabilities.Energy.ITEM);
        if (handler == null) {
            throw new AssertionError("Missing FE item capability in player slot " + invSlot);
        }
        return (int) handler.getCapacityAsLong();
    }

    /** Inserts FE into the 26.x item capability via {@link ItemAccess} and a root {@link Transaction}. */
    public static int itemFeReceive(Player player, int invSlot, int offeredFe, boolean simulate) {
        EnergyHandler handler = ItemAccess.forPlayerSlot(player, invSlot).getCapability(Capabilities.Energy.ITEM);
        if (handler == null) {
            throw new AssertionError("Missing FE item capability in player slot " + invSlot);
        }
        try (var tx = Transaction.openRoot()) {
            int accepted = handler.insert(offeredFe, tx);
            if (!simulate) {
                tx.commit();
            }
            return accepted;
        }
    }

    /** Attempts to extract FE from the 26.x item capability via {@link ItemAccess} and a root {@link Transaction}. */
    public static int itemFeExtract(Player player, int invSlot, int maxExtract, boolean simulate) {
        EnergyHandler handler = ItemAccess.forPlayerSlot(player, invSlot).getCapability(Capabilities.Energy.ITEM);
        if (handler == null) {
            throw new AssertionError("Missing FE item capability in player slot " + invSlot);
        }
        try (var tx = Transaction.openRoot()) {
            int extracted = handler.extract(maxExtract, tx);
            if (!simulate) {
                tx.commit();
            }
            return extracted;
        }
    }

    /** Invokes {@code menu.clicked(..., ContainerInput.PICKUP, player)} on 26.x. */
    public static void clickMenuPickup(AbstractContainerMenu menu, int slotId, int button, Player player) {
        menu.clicked(slotId, button, ContainerInput.PICKUP, player);
    }

    /** Invokes {@code menu.clicked(..., ContainerInput.QUICK_MOVE, player)} on 26.x. */
    public static void clickMenuQuickMove(AbstractContainerMenu menu, int slotId, int button, Player player) {
        menu.clicked(slotId, button, ContainerInput.QUICK_MOVE, player);
    }

    /** Invokes {@code menu.clicked(..., ContainerInput.PICKUP_ALL, player)} on 26.x. */
    public static void clickMenuPickupAll(AbstractContainerMenu menu, int slotId, int button, Player player) {
        menu.clicked(slotId, button, ContainerInput.PICKUP_ALL, player);
    }

    /** Invokes {@code menu.clicked(..., ContainerInput.SWAP, player)} on 26.x. */
    public static void clickMenuSwap(AbstractContainerMenu menu, int slotId, int hotbarButton, Player player) {
        menu.clicked(slotId, hotbarButton, ContainerInput.SWAP, player);
    }

    /** Invokes {@code menu.clicked(..., ContainerInput.THROW, player)} on 26.x. */
    public static void clickMenuThrow(AbstractContainerMenu menu, int slotId, int button, Player player) {
        menu.clicked(slotId, button, ContainerInput.THROW, player);
    }
}
