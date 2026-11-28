package com.logisticscraft.lc2.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * NeoForge 1.21.1 version-specific QA helpers.
 */
public final class QaPlatform {

    private QaPlatform() {
    }

    /** Returns all registered recipe IDs in the live RecipeManager as qualified strings. */
    public static Set<String> allRecipeIds(MinecraftServer server) {
        Set<String> ids = new TreeSet<>();
        server.getRecipeManager().getRecipes().forEach(holder -> ids.add(holder.id().toString()));
        return ids;
    }

    /** Invokes live entity interaction on 1.21.1. */
    public static InteractionResult interactEntity(Entity entity, Player player) {
        return entity.interact(player, InteractionHand.MAIN_HAND);
    }

    /** Spawns a fresh Villager passenger at the given world position. */
    public static Mob spawnPassenger(ServerLevel level, double x, double y, double z) {
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) {
            throw new AssertionError("Failed to create Villager passenger");
        }
        villager.setPos(x, y, z);
        villager.setNoAi(true);
        if (!level.addFreshEntity(villager)) {
            throw new AssertionError("Failed to add Villager passenger to level");
        }
        return villager;
    }

    /** Teleports a player to exact coordinates and view angles on 1.21.1. */
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
        player.teleportTo(level, x, y, z, yaw, pitch);
    }

    /** Returns the world spawn position. */
    public static BlockPos spawnPos(ServerLevel level) {
        return level.getSharedSpawnPos();
    }

    /** Returns the qualified biome ID at {@code pos}. */
    public static String biomeId(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("unknown");
    }

    /** Reads the block's vanilla comparator analog output signal (0..15). */
    public static int analogOutputSignal(ServerLevel level, BlockPos pos, Direction side) {
        return level.getBlockState(pos).getAnalogOutputSignal(level, pos);
    }

    /** Non-destructively reads all non-empty ItemStacks stored in the container/capability at {@code pos}. */
    public static List<ItemStack> readAllStacks(ServerLevel level, BlockPos pos) {
        List<ItemStack> result = new ArrayList<>();
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        if (handler != null) {
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack s = handler.getStackInSlot(i);
                if (!s.isEmpty()) {
                    result.add(s.copy());
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

    /** Loads a legacy v1 station NBT payload (without {@code layoutVersion}) into {@code be} on 1.21.1. */
    public static void loadLegacyV1Station(
            ServerLevel level,
            BlockEntity be,
            List<ItemStack> legacyItems,
            CompoundTag legacyLc2Tag
    ) {
        NonNullList<ItemStack> list = NonNullList.withSize(legacyItems.size(), ItemStack.EMPTY);
        for (int i = 0; i < legacyItems.size(); i++) {
            list.set(i, legacyItems.get(i).copy());
        }
        CompoundTag root = new CompoundTag();
        ContainerHelper.saveAllItems(root, list, level.registryAccess());
        root.put("trera", legacyLc2Tag.copy());
        be.loadCustomOnly(root, level.registryAccess());
    }

    /** Returns {@code true} if the block at {@code pos} exposes an FE block capability on {@code side}. */
    public static boolean hasBlockFeCapability(ServerLevel level, BlockPos pos, Direction side) {
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side) != null;
    }

    /** Reads the FE energy stored in the block capability at {@code pos} on {@code side}. */
    public static int blockFeStored(ServerLevel level, BlockPos pos, Direction side) {
        IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
        if (storage == null) {
            throw new AssertionError("Missing FE block capability at " + pos + " side=" + side);
        }
        return storage.getEnergyStored();
    }

    /** Reads the FE capacity of the block capability at {@code pos} on {@code side}. */
    public static int blockFeCapacity(ServerLevel level, BlockPos pos, Direction side) {
        IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
        if (storage == null) {
            throw new AssertionError("Missing FE block capability at " + pos + " side=" + side);
        }
        return storage.getMaxEnergyStored();
    }

    /** Inserts FE into the block capability at {@code pos} on {@code side}. */
    public static int blockFeReceive(ServerLevel level, BlockPos pos, Direction side, int offeredFe, boolean simulate) {
        IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
        if (storage == null) {
            throw new AssertionError("Missing FE block capability at " + pos + " side=" + side);
        }
        return storage.receiveEnergy(offeredFe, simulate);
    }

    /** Attempts to extract FE from the block capability at {@code pos} on {@code side}. */
    public static int blockFeExtract(ServerLevel level, BlockPos pos, Direction side, int maxExtract, boolean simulate) {
        IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
        if (storage == null) {
            throw new AssertionError("Missing FE block capability at " + pos + " side=" + side);
        }
        return storage.extractEnergy(maxExtract, simulate);
    }

    /** Returns {@code true} if the item in {@code player}'s inventory slot {@code invSlot} exposes FE capability. */
    public static boolean hasItemFeCapability(Player player, int invSlot) {
        return player.getInventory().getItem(invSlot).getCapability(Capabilities.EnergyStorage.ITEM) != null;
    }

    /** Reads the FE energy stored in the item capability in {@code player}'s inventory slot {@code invSlot}. */
    public static int itemFeStored(Player player, int invSlot) {
        IEnergyStorage storage = player.getInventory().getItem(invSlot).getCapability(Capabilities.EnergyStorage.ITEM);
        if (storage == null) {
            throw new AssertionError("Missing FE item capability in player slot " + invSlot);
        }
        return storage.getEnergyStored();
    }

    /** Reads the FE capacity of the item capability in {@code player}'s inventory slot {@code invSlot}. */
    public static int itemFeCapacity(Player player, int invSlot) {
        IEnergyStorage storage = player.getInventory().getItem(invSlot).getCapability(Capabilities.EnergyStorage.ITEM);
        if (storage == null) {
            throw new AssertionError("Missing FE item capability in player slot " + invSlot);
        }
        return storage.getMaxEnergyStored();
    }

    /** Inserts FE into the item capability in {@code player}'s inventory slot {@code invSlot}. */
    public static int itemFeReceive(Player player, int invSlot, int offeredFe, boolean simulate) {
        IEnergyStorage storage = player.getInventory().getItem(invSlot).getCapability(Capabilities.EnergyStorage.ITEM);
        if (storage == null) {
            throw new AssertionError("Missing FE item capability in player slot " + invSlot);
        }
        return storage.receiveEnergy(offeredFe, simulate);
    }

    /** Attempts to extract FE from the item capability in {@code player}'s inventory slot {@code invSlot}. */
    public static int itemFeExtract(Player player, int invSlot, int maxExtract, boolean simulate) {
        IEnergyStorage storage = player.getInventory().getItem(invSlot).getCapability(Capabilities.EnergyStorage.ITEM);
        if (storage == null) {
            throw new AssertionError("Missing FE item capability in player slot " + invSlot);
        }
        return storage.extractEnergy(maxExtract, simulate);
    }

    /** Invokes {@code menu.clicked(..., ClickType.PICKUP, player)} on 1.21.1. */
    public static void clickMenuPickup(AbstractContainerMenu menu, int slotId, int button, Player player) {
        menu.clicked(slotId, button, ClickType.PICKUP, player);
    }

    /** Invokes {@code menu.clicked(..., ClickType.QUICK_MOVE, player)} on 1.21.1. */
    public static void clickMenuQuickMove(AbstractContainerMenu menu, int slotId, int button, Player player) {
        menu.clicked(slotId, button, ClickType.QUICK_MOVE, player);
    }

    /** Invokes {@code menu.clicked(..., ClickType.PICKUP_ALL, player)} on 1.21.1. */
    public static void clickMenuPickupAll(AbstractContainerMenu menu, int slotId, int button, Player player) {
        menu.clicked(slotId, button, ClickType.PICKUP_ALL, player);
    }

    /** Invokes {@code menu.clicked(..., ClickType.SWAP, player)} on 1.21.1. */
    public static void clickMenuSwap(AbstractContainerMenu menu, int slotId, int hotbarButton, Player player) {
        menu.clicked(slotId, hotbarButton, ClickType.SWAP, player);
    }

    /** Invokes {@code menu.clicked(..., ClickType.THROW, player)} on 1.21.1. */
    public static void clickMenuThrow(AbstractContainerMenu menu, int slotId, int button, Player player) {
        menu.clicked(slotId, button, ClickType.THROW, player);
    }
}
