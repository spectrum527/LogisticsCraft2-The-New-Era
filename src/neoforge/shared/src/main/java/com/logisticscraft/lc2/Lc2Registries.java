package com.logisticscraft.lc2;

import com.logisticscraft.lc2.block.CargoStationBlock;
import com.logisticscraft.lc2.block.CommBaseStationBlock;
import com.logisticscraft.lc2.block.SmartPortBlock;
import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.blockentity.CommBaseStationBlockEntity;
import com.logisticscraft.lc2.blockentity.SmartPortBlockEntity;
import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.entity.CargoDroneEntity;
import com.logisticscraft.lc2.entity.ElectricCargoBoatEntity;
import com.logisticscraft.lc2.item.ElectricCargoBoatItem;
import com.logisticscraft.lc2.item.OperationTerminalItem;
import com.logisticscraft.lc2.menu.CargoStationMenu;
import com.logisticscraft.lc2.menu.ElectricCargoBoatMenu;
import com.logisticscraft.lc2.menu.SmartPortMenu;
import com.logisticscraft.lc2.platform.Lc2Native;
import com.logisticscraft.lc2.platform.Lc2TooltipBlockItem;
import com.logisticscraft.lc2.platform.Lc2TooltipItem;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Canonical NeoForge registries for all LC2 blocks, items, block entities, entities, menus,
 * and the LogisticsCraft² creative tab.
 */
public final class Lc2Registries {

    private static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(LogisticsConstants.MOD_ID);
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(LogisticsConstants.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LogisticsConstants.MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, LogisticsConstants.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, LogisticsConstants.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, LogisticsConstants.MOD_ID);

    // --- Blocks (2) ---

    public static final DeferredBlock<SmartPortBlock> SMART_PORT_BLOCK = Lc2Native.registerBlock(
            BLOCKS,
            "smart_port",
            SmartPortBlock::new,
            () -> Block.Properties.of()
                    .strength(3.5F, 8.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
    );

    public static final DeferredBlock<CargoStationBlock> CARGO_STATION_BLOCK = Lc2Native.registerBlock(
            BLOCKS,
            "cargo_station",
            CargoStationBlock::new,
            () -> Block.Properties.of()
                    .strength(4.0F, 10.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
    );

    public static final DeferredBlock<CommBaseStationBlock> COMM_BASE_STATION_BLOCK = Lc2Native.registerBlock(
            BLOCKS,
            "comm_base_station",
            CommBaseStationBlock::new,
            () -> Block.Properties.of()
                    .strength(3.5F, 8.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()
    );

    // --- Items (11) ---

    public static final DeferredItem<Lc2TooltipBlockItem> SMART_PORT_ITEM = Lc2Native.registerBlockItem(
            ITEMS,
            "smart_port",
            SMART_PORT_BLOCK,
            List.of(
                    "tooltip.lc2.smart_port.line1",
                    "tooltip.lc2.smart_port.line2",
                    "tooltip.lc2.smart_port.line3"
            )
    );

    public static final DeferredItem<Lc2TooltipBlockItem> CARGO_STATION_ITEM = Lc2Native.registerBlockItem(
            ITEMS,
            "cargo_station",
            CARGO_STATION_BLOCK,
            List.of(
                    "tooltip.lc2.cargo_station.line1",
                    "tooltip.lc2.cargo_station.line2",
                    "tooltip.lc2.cargo_station.line3"
            )
    );

    public static final DeferredItem<Lc2TooltipBlockItem> COMM_BASE_STATION_ITEM = Lc2Native.registerBlockItem(
            ITEMS,
            "comm_base_station",
            COMM_BASE_STATION_BLOCK,
            List.of(
                    "tooltip.lc2.comm_base_station.line1",
                    "tooltip.lc2.comm_base_station.line2",
                    "tooltip.lc2.comm_base_station.line3"
            )
    );

    public static final DeferredItem<OperationTerminalItem> OPERATION_TERMINAL_ITEM = Lc2Native.registerItem(
            ITEMS,
            "operation_terminal",
            props -> new OperationTerminalItem(
                    props.stacksTo(1),
                    List.of(
                            "tooltip.lc2.operation_terminal.line1",
                            "tooltip.lc2.operation_terminal.line2",
                            "tooltip.lc2.operation_terminal.line3"
                    )
            ),
            Item.Properties::new
    );

    public static final DeferredItem<ElectricCargoBoatItem> ELECTRIC_CARGO_BOAT_ITEM = Lc2Native.registerItem(
            ITEMS,
            "electric_cargo_boat",
            props -> new ElectricCargoBoatItem(
                    props.stacksTo(1),
                    List.of(
                            "tooltip.lc2.electric_cargo_boat.line1",
                            "tooltip.lc2.electric_cargo_boat.line2",
                            "tooltip.lc2.electric_cargo_boat.line3"
                    )
            ),
            Item.Properties::new
    );

    public static final DeferredItem<Lc2TooltipItem> TRANSPORT_DRONE_ITEM = Lc2Native.registerItem(
            ITEMS,
            "transport_drone",
            props -> new Lc2TooltipItem(
                    props.stacksTo(1),
                    List.of(
                            "tooltip.lc2.transport_drone.line1",
                            "tooltip.lc2.transport_drone.line2"
                    )
            ),
            Item.Properties::new
    );

    public static final DeferredItem<Lc2TooltipItem> HEAVY_TRANSPORT_DRONE_ITEM = Lc2Native.registerItem(
            ITEMS,
            "heavy_transport_drone",
            props -> new Lc2TooltipItem(
                    props.stacksTo(1),
                    List.of(
                            "tooltip.lc2.heavy_transport_drone.line1",
                            "tooltip.lc2.heavy_transport_drone.line2"
                    )
            ),
            Item.Properties::new
    );

    public static final DeferredItem<Lc2TooltipItem> RF_CHIP_1_ITEM = Lc2Native.registerItem(
            ITEMS,
            "rf_chip_1",
            props -> new Lc2TooltipItem(
                    props.stacksTo(1),
                    List.of(
                            "tooltip.lc2.rf_chip_1.line1",
                            "tooltip.lc2.rf_chip_1.line2"
                    )
            ),
            Item.Properties::new
    );

    public static final DeferredItem<Lc2TooltipItem> RF_CHIP_2_ITEM = Lc2Native.registerItem(
            ITEMS,
            "rf_chip_2",
            props -> new Lc2TooltipItem(
                    props.stacksTo(1),
                    List.of(
                            "tooltip.lc2.rf_chip_2.line1",
                            "tooltip.lc2.rf_chip_2.line2"
                    )
            ),
            Item.Properties::new
    );

    public static final DeferredItem<Lc2TooltipItem> RF_CHIP_3_ITEM = Lc2Native.registerItem(
            ITEMS,
            "rf_chip_3",
            props -> new Lc2TooltipItem(
                    props.stacksTo(1),
                    List.of(
                            "tooltip.lc2.rf_chip_3.line1",
                            "tooltip.lc2.rf_chip_3.line2"
                    )
            ),
            Item.Properties::new
    );

    public static final DeferredItem<Lc2TooltipItem> CHUNK_LOADING_UPGRADE_ITEM = Lc2Native.registerItem(
            ITEMS,
            "chunk_loading_upgrade",
            props -> new Lc2TooltipItem(
                    props.stacksTo(16),
                    List.of(
                            "tooltip.lc2.chunk_loading_upgrade.line1",
                            "tooltip.lc2.chunk_loading_upgrade.line2"
                    )
            ),
            Item.Properties::new
    );

    // --- Block Entities (2) ---

    public static final Supplier<BlockEntityType<SmartPortBlockEntity>> SMART_PORT_BE =
            BLOCK_ENTITIES.register(
                    "smart_port",
                    () -> Lc2Native.createBlockEntityType(SmartPortBlockEntity::new, SMART_PORT_BLOCK.get())
            );

    public static final Supplier<BlockEntityType<CargoStationBlockEntity>> CARGO_STATION_BE =
            BLOCK_ENTITIES.register(
                    "cargo_station",
                    () -> Lc2Native.createBlockEntityType(CargoStationBlockEntity::new, CARGO_STATION_BLOCK.get())
            );

    public static final Supplier<BlockEntityType<CommBaseStationBlockEntity>> COMM_BASE_STATION_BE =
            BLOCK_ENTITIES.register(
                    "comm_base_station",
                    () -> Lc2Native.createBlockEntityType(
                            CommBaseStationBlockEntity::new,
                            COMM_BASE_STATION_BLOCK.get()
                    )
            );

    // --- Entities (2) ---

    public static final Supplier<EntityType<ElectricCargoBoatEntity>> ELECTRIC_CARGO_BOAT_ENTITY =
            ENTITIES.register(
                    "electric_cargo_boat",
                    () -> Lc2Native.createEntityType(
                            "electric_cargo_boat",
                            ElectricCargoBoatEntity::new,
                            1.375F,
                            0.5625F,
                            10,
                            1
                    )
            );

    public static final Supplier<EntityType<CargoDroneEntity>> CARGO_DRONE_ENTITY =
            ENTITIES.register(
                    "cargo_drone",
                    () -> Lc2Native.createEntityType(
                            "cargo_drone",
                            CargoDroneEntity::new,
                            0.9F,
                            0.65F,
                            12,
                            1
                    )
            );

    // --- Menus (3) ---

    public static final Supplier<MenuType<SmartPortMenu>> SMART_PORT_MENU =
            MENUS.register("smart_port", () -> IMenuTypeExtension.create(SmartPortMenu::new));

    public static final Supplier<MenuType<CargoStationMenu>> CARGO_STATION_MENU =
            MENUS.register("cargo_station", () -> IMenuTypeExtension.create(CargoStationMenu::new));

    public static final Supplier<MenuType<ElectricCargoBoatMenu>> ELECTRIC_CARGO_BOAT_MENU =
            MENUS.register("electric_cargo_boat", () -> IMenuTypeExtension.create(ElectricCargoBoatMenu::new));

    // --- Creative Tab (1) ---

    public static final Supplier<CreativeModeTab> MAIN_TAB = TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.lc2.main"))
                    .icon(() -> new ItemStack(SMART_PORT_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(SMART_PORT_ITEM.get());
                        output.accept(CARGO_STATION_ITEM.get());
                        output.accept(COMM_BASE_STATION_ITEM.get());
                        output.accept(OPERATION_TERMINAL_ITEM.get());
                        output.accept(OperationTerminalItem.createChargedStack(OPERATION_TERMINAL_ITEM.get()));
                        output.accept(ELECTRIC_CARGO_BOAT_ITEM.get());
                        output.accept(TRANSPORT_DRONE_ITEM.get());
                        output.accept(HEAVY_TRANSPORT_DRONE_ITEM.get());
                        output.accept(RF_CHIP_1_ITEM.get());
                        output.accept(RF_CHIP_2_ITEM.get());
                        output.accept(RF_CHIP_3_ITEM.get());
                        output.accept(CHUNK_LOADING_UPGRADE_ITEM.get());
                    })
                    .build()
    );

    private Lc2Registries() {
    }

    /** Attaches all DeferredRegisters to the mod event bus. */
    public static void install(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ENTITIES.register(modBus);
        MENUS.register(modBus);
        TABS.register(modBus);
    }

    /** Registers sided block entity item and EU/FE energy capabilities on the mod event bus. */
    public static void registerCapabilities(IEventBus modBus) {
        Lc2Native.registerCapabilities(
                modBus,
                SMART_PORT_BE,
                CARGO_STATION_BE,
                COMM_BASE_STATION_BE,
                OPERATION_TERMINAL_ITEM::get
        );
    }
}
