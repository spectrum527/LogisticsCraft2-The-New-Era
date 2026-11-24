package com.logisticscraft.lc2.forge;

import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.forge.block.CargoStationBlock;
import com.logisticscraft.lc2.forge.block.CargoStationBlockEntity;
import com.logisticscraft.lc2.forge.block.CommBaseStationBlock;
import com.logisticscraft.lc2.forge.block.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.forge.block.SmartPortBlock;
import com.logisticscraft.lc2.forge.block.SmartPortBlockEntity;
import com.logisticscraft.lc2.forge.entity.CargoDroneEntity;
import com.logisticscraft.lc2.forge.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.forge.gui.CargoStationMenu;
import com.logisticscraft.lc2.forge.gui.ElectricCargoBoatMenu;
import com.logisticscraft.lc2.forge.gui.SmartPortMenu;
import com.logisticscraft.lc2.forge.item.ElectricCargoBoatItem;
import com.logisticscraft.lc2.forge.item.Lc2BlockItem;
import com.logisticscraft.lc2.forge.item.Lc2TooltipItem;
import com.logisticscraft.lc2.forge.item.OperationTerminalItem;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Canonical Forge 1.19.2 registries for all LogisticsCraft² blocks, block entities, entities,
 * items, menus, and creative mode tab.
 */
public final class Lc2Registries {

    private Lc2Registries() {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, LogisticsConstants.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, LogisticsConstants.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, LogisticsConstants.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, LogisticsConstants.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, LogisticsConstants.MOD_ID);

    // --- Creative Tab ---

    public static final CreativeModeTab CREATIVE_TAB = new CreativeModeTab(LogisticsConstants.MOD_ID) {
        @Override
        public ItemStack makeIcon() {
            return new ItemStack(SMART_PORT_ITEM.get());
        }

        @Override
        public void fillItemList(NonNullList<ItemStack> items) {
            items.add(new ItemStack(SMART_PORT_ITEM.get()));
            items.add(new ItemStack(CARGO_STATION_ITEM.get()));
            items.add(new ItemStack(COMM_BASE_STATION_ITEM.get()));
            items.add(new ItemStack(OPERATION_TERMINAL_ITEM.get()));
            items.add(OperationTerminalItem.createChargedStack());
            items.add(new ItemStack(ELECTRIC_CARGO_BOAT_ITEM.get()));
            items.add(new ItemStack(TRANSPORT_DRONE_ITEM.get()));
            items.add(new ItemStack(HEAVY_TRANSPORT_DRONE_ITEM.get()));
            items.add(new ItemStack(RF_CHIP_1_ITEM.get()));
            items.add(new ItemStack(RF_CHIP_2_ITEM.get()));
            items.add(new ItemStack(RF_CHIP_3_ITEM.get()));
            items.add(new ItemStack(CHUNK_LOADING_UPGRADE_ITEM.get()));
        }
    };

    // --- Blocks (3) ---

    public static final RegistryObject<Block> SMART_PORT_BLOCK =
            BLOCKS.register("smart_port", SmartPortBlock::new);

    public static final RegistryObject<Block> CARGO_STATION_BLOCK =
            BLOCKS.register("cargo_station", CargoStationBlock::new);

    public static final RegistryObject<Block> COMM_BASE_STATION_BLOCK =
            BLOCKS.register("comm_base_station", CommBaseStationBlock::new);

    // --- Block Entities (3) ---

    public static final RegistryObject<BlockEntityType<SmartPortBlockEntity>> SMART_PORT_BE =
            BLOCK_ENTITIES.register("smart_port", () ->
                    BlockEntityType.Builder.of(SmartPortBlockEntity::new, SMART_PORT_BLOCK.get()).build(null));

    public static final RegistryObject<BlockEntityType<CargoStationBlockEntity>> CARGO_STATION_BE =
            BLOCK_ENTITIES.register("cargo_station", () ->
                    BlockEntityType.Builder.of(CargoStationBlockEntity::new, CARGO_STATION_BLOCK.get()).build(null));

    public static final RegistryObject<BlockEntityType<CommBaseStationBlockEntity>> COMM_BASE_STATION_BE =
            BLOCK_ENTITIES.register("comm_base_station", () ->
                    BlockEntityType.Builder.of(CommBaseStationBlockEntity::new, COMM_BASE_STATION_BLOCK.get()).build(null));

    // --- Entities (2) ---

    public static final RegistryObject<EntityType<ElectricCargoBoatEntity>> ELECTRIC_CARGO_BOAT_ENTITY =
            ENTITIES.register("electric_cargo_boat", () ->
                    EntityType.Builder.<ElectricCargoBoatEntity>of(ElectricCargoBoatEntity::new, MobCategory.MISC)
                            .sized(1.375F, 0.5625F)
                            .clientTrackingRange(10)
                            .updateInterval(3)
                            .build(LogisticsConstants.ELECTRIC_CARGO_BOAT_ID));

    public static final RegistryObject<EntityType<CargoDroneEntity>> CARGO_DRONE_ENTITY =
            ENTITIES.register("cargo_drone", () ->
                    EntityType.Builder.<CargoDroneEntity>of(CargoDroneEntity::new, MobCategory.MISC)
                            .sized(0.85F, 0.65F)
                            .clientTrackingRange(12)
                            .updateInterval(2)
                            .build(LogisticsConstants.CARGO_DRONE_ENTITY_ID));

    // --- Items (11: 3 BlockItems + 8 Items) ---

    public static final RegistryObject<Item> SMART_PORT_ITEM =
            ITEMS.register("smart_port", () -> new Lc2BlockItem(
                    SMART_PORT_BLOCK.get(),
                    tabProps(),
                    "tooltip.lc2.smart_port.line1",
                    "tooltip.lc2.smart_port.line2",
                    "tooltip.lc2.smart_port.line3"
            ));

    public static final RegistryObject<Item> CARGO_STATION_ITEM =
            ITEMS.register("cargo_station", () -> new Lc2BlockItem(
                    CARGO_STATION_BLOCK.get(),
                    tabProps(),
                    "tooltip.lc2.cargo_station.line1",
                    "tooltip.lc2.cargo_station.line2",
                    "tooltip.lc2.cargo_station.line3"
            ));

    public static final RegistryObject<Item> COMM_BASE_STATION_ITEM =
            ITEMS.register("comm_base_station", () -> new Lc2BlockItem(
                    COMM_BASE_STATION_BLOCK.get(),
                    tabProps(),
                    "tooltip.lc2.comm_base_station.line1",
                    "tooltip.lc2.comm_base_station.line2",
                    "tooltip.lc2.comm_base_station.line3"
            ));

    public static final RegistryObject<Item> OPERATION_TERMINAL_ITEM =
            ITEMS.register("operation_terminal", () -> new OperationTerminalItem(tabProps().stacksTo(1)));

    public static final RegistryObject<Item> ELECTRIC_CARGO_BOAT_ITEM =
            ITEMS.register("electric_cargo_boat", () -> new ElectricCargoBoatItem(tabProps().stacksTo(1)));

    public static final RegistryObject<Item> TRANSPORT_DRONE_ITEM =
            ITEMS.register("transport_drone", () -> new Lc2TooltipItem(
                    tabProps().stacksTo(1),
                    "tooltip.lc2.transport_drone.line1",
                    "tooltip.lc2.transport_drone.line2"
            ));

    public static final RegistryObject<Item> HEAVY_TRANSPORT_DRONE_ITEM =
            ITEMS.register("heavy_transport_drone", () -> new Lc2TooltipItem(
                    tabProps().stacksTo(1),
                    "tooltip.lc2.heavy_transport_drone.line1",
                    "tooltip.lc2.heavy_transport_drone.line2"
            ));

    public static final RegistryObject<Item> RF_CHIP_1_ITEM =
            ITEMS.register("rf_chip_1", () -> new Lc2TooltipItem(
                    tabProps().stacksTo(1),
                    "tooltip.lc2.rf_chip_1.line1",
                    "tooltip.lc2.rf_chip_1.line2"
            ));

    public static final RegistryObject<Item> RF_CHIP_2_ITEM =
            ITEMS.register("rf_chip_2", () -> new Lc2TooltipItem(
                    tabProps().stacksTo(1),
                    "tooltip.lc2.rf_chip_2.line1",
                    "tooltip.lc2.rf_chip_2.line2"
            ));

    public static final RegistryObject<Item> RF_CHIP_3_ITEM =
            ITEMS.register("rf_chip_3", () -> new Lc2TooltipItem(
                    tabProps().stacksTo(1),
                    "tooltip.lc2.rf_chip_3.line1",
                    "tooltip.lc2.rf_chip_3.line2"
            ));

    public static final RegistryObject<Item> CHUNK_LOADING_UPGRADE_ITEM =
            ITEMS.register("chunk_loading_upgrade", () -> new Lc2TooltipItem(
                    tabProps().stacksTo(16),
                    "tooltip.lc2.chunk_loading_upgrade.line1",
                    "tooltip.lc2.chunk_loading_upgrade.line2"
            ));

    // --- Menus (3) ---

    public static final RegistryObject<MenuType<SmartPortMenu>> SMART_PORT_MENU =
            MENUS.register("smart_port", () -> IForgeMenuType.create(SmartPortMenu::new));

    public static final RegistryObject<MenuType<CargoStationMenu>> CARGO_STATION_MENU =
            MENUS.register("cargo_station", () -> IForgeMenuType.create(CargoStationMenu::new));

    public static final RegistryObject<MenuType<ElectricCargoBoatMenu>> ELECTRIC_CARGO_BOAT_MENU =
            MENUS.register("electric_cargo_boat", () -> IForgeMenuType.create(ElectricCargoBoatMenu::new));

    private static Item.Properties tabProps() {
        return new Item.Properties().tab(CREATIVE_TAB);
    }

    /** Registers all deferred registries on the Forge mod event bus. */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        MENUS.register(modBus);
    }
}
