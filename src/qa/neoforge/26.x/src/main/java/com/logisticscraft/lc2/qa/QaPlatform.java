package com.logisticscraft.lc2.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;

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
}
