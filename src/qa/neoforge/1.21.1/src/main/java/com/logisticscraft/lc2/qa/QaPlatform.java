package com.logisticscraft.lc2.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
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
}
