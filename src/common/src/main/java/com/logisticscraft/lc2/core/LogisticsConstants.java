package com.logisticscraft.lc2.core;

/**
 * Canonical constants, registry identifiers, electrical ratings, and simulation parameters
 * shared across all LogisticsCraft² platform targets.
 */
public final class LogisticsConstants {

    private LogisticsConstants() {
    }

    // --- Mod & Registry IDs ---

    /** Primary mod namespace. */
    public static final String MOD_ID = "trera";

    /** IndustrialCraft² addon target namespace. */
    public static final String IC2_MOD_ID = "mhera";

    /** Smart Port block and block entity registry ID. */
    public static final String SMART_PORT_ID = "trera:smart_port";

    /** Cargo Station block and block entity registry ID. */
    public static final String CARGO_STATION_ID = "trera:cargo_station";

    /** Electric Smart Cargo Boat item and entity registry ID. */
    public static final String ELECTRIC_CARGO_BOAT_ID = "trera:electric_cargo_boat";

    /** Cargo Drone entity registry ID (shared across Standard and Heavy variants). */
    public static final String CARGO_DRONE_ENTITY_ID = "trera:cargo_drone";

    /** Standard Transport Drone item registry ID. */
    public static final String TRANSPORT_DRONE_ID = "trera:transport_drone";

    /** Heavy Transport Drone item registry ID. */
    public static final String HEAVY_TRANSPORT_DRONE_ID = "trera:heavy_transport_drone";

    /** RF Chip Tier I item registry ID (256-block Manhattan range). */
    public static final String RF_CHIP_1_ID = "trera:rf_chip_1";

    /** RF Chip Tier II item registry ID (512-block Manhattan range). */
    public static final String RF_CHIP_2_ID = "trera:rf_chip_2";

    /** RF Chip Tier III item registry ID (1024-block Manhattan range). */
    public static final String RF_CHIP_3_ID = "trera:rf_chip_3";

    /** Chunk Loading Upgrade item registry ID. */
    public static final String CHUNK_LOADING_UPGRADE_ID = "trera:chunk_loading_upgrade";

    /** Communication Base Station block and block entity registry ID. */
    public static final String COMM_BASE_STATION_ID = "trera:comm_base_station";

    /** Operation Terminal handheld item registry ID. */
    public static final String OPERATION_TERMINAL_ID = "trera:operation_terminal";

    /** IC2 Single-Use Battery item registry ID accepted by both stations. */
    public static final String SINGLE_USE_BATTERY_ID = "mhera:single_use_battery";

    /** Optional item tag allowing modpacks to extend the single-use battery slot. */
    public static final String SINGLE_USE_BATTERIES_TAG = "trera:single_use_batteries";

    // --- Supported Upgrade Item IDs ---

    /** IC2 Transformer Upgrade (+1 voltage tier). */
    public static final String UPGRADE_TRANSFORMER = "mhera:transformer_upgrade";

    /** IC2 Energy Storage Upgrade (+10,000 EU capacity). */
    public static final String UPGRADE_ENERGY_STORAGE = "mhera:energy_storage_upgrade";

    /** IC2 Overclocker Upgrade (throughput and cruise speed boost). */
    public static final String UPGRADE_OVERCLOCKER = "mhera:overclocker_upgrade";

    /** IC2 Ejector Upgrade. */
    public static final String UPGRADE_EJECTOR = "mhera:ejector_upgrade";

    /** IC2 Advanced Ejector Upgrade. */
    public static final String UPGRADE_ADVANCED_EJECTOR = "mhera:advanced_ejector_upgrade";

    /** IC2 Pulling Upgrade. */
    public static final String UPGRADE_PULLING = "mhera:pulling_upgrade";

    /** IC2 Advanced Pulling Upgrade. */
    public static final String UPGRADE_ADVANCED_PULLING = "mhera:advanced_pulling_upgrade";

    /** IC2 Redstone Inverter Upgrade. */
    public static final String UPGRADE_REDSTONE_INVERTER = "mhera:redstone_inverter_upgrade";

    /** LC2 Chunk Loading Upgrade (3x3 chunks, 2 EU/t standby). */
    public static final String UPGRADE_CHUNK_LOADING = CHUNK_LOADING_UPGRADE_ID;

    // --- Electrical & Upgrade Parameters ---

    /** Base internal EU buffer capacity of Smart Port and Cargo Station. */
    public static final double BASE_ENERGY_CAPACITY_EU = 14_400.0;

    /** Additional EU buffer capacity per {@code ic2:energy_storage_upgrade}. */
    public static final double ENERGY_STORAGE_BONUS_EU = 10_000.0;

    /** Base electrical input tier (1 = LV, 32 EU/t). */
    public static final int BASE_INPUT_TIER = 1;

    /** Maximum electrical input tier (5 = IV, 8192 EU/t). */
    public static final int MAX_INPUT_TIER = 5;

    /** Base LV packet limit in EU/t. */
    public static final int LV_PACKET_LIMIT_EU = 32;

    /** Standby EU/t consumed while a chunk loading upgrade actively force-loads chunks. */
    public static final double CHUNK_LOADER_STANDBY_DRAIN_EU_PER_TICK = 2.0;

    /** Chunk radius for the chunk loading upgrade (radius 1 = 3x3 chunk area). */
    public static final int CHUNK_LOADER_RADIUS = 1;

    /** Number of machine upgrade slots on Smart Port and Cargo Station. */
    public static final int MACHINE_UPGRADE_SLOTS = 4;

    /** Maximum stack size in the single-use battery slot. */
    public static final int SINGLE_USE_BATTERY_STACK_LIMIT = 64;

    /** Base item transfer rate in items per tick with zero overclockers. */
    public static final int BASE_TRANSFER_RATE_PER_TICK = 4;

    /** Additional item transfer rate per tick per overclocker upgrade. */
    public static final int OVERCLOCKER_TRANSFER_BONUS_PER_TICK = 8;

    /** Maximum item transfer rate in items per tick. */
    public static final int MAX_TRANSFER_RATE_PER_TICK = 64;

    /** Fractional cruise speed bonus per overclocker upgrade. */
    public static final double OVERCLOCKER_SPEED_BONUS_PER_UPGRADE = 0.08;

    /** Maximum cumulative cruise speed bonus from overclocker upgrades (+40%). */
    public static final double MAX_OVERCLOCKER_SPEED_BONUS = 0.40;

    // --- Station & Vehicle Capacity / Speed Parameters ---

    /** Number of physical drone landing bays per Cargo Station. */
    public static final int CARGO_STATION_DRONE_BAYS = 6;

    /** Starting cargo slot index for the Outbound (待发货区域) zone on both station types (inclusive). */
    public static final int STATION_OUTBOUND_START = 0;

    /** Number of Outbound (待发货区域) cargo slots on both Smart Port and Cargo Station (3x9, indices 0..26). */
    public static final int STATION_OUTBOUND_SLOTS = 27;

    /** Starting cargo slot index for the Storage (存储区域) zone on both station types (inclusive). */
    public static final int STATION_STORAGE_START = STATION_OUTBOUND_SLOTS;

    /** Number of Storage (存储区域) cargo slots on both Smart Port and Cargo Station (3x9, indices 27..53). */
    public static final int STATION_STORAGE_SLOTS = 27;

    /** Total 2-zone cargo slots (Outbound 0..26 + Storage 27..53 = 54) on both Smart Port and Cargo Station. */
    public static final int STATION_TOTAL_CARGO_SLOTS = STATION_OUTBOUND_SLOTS + STATION_STORAGE_SLOTS;

    /** Total machine-side inventory slots on a Smart Port (54 cargo + 1 battery + 4 upgrades = 59). */
    public static final int SMART_PORT_MACHINE_SLOTS = STATION_TOTAL_CARGO_SLOTS + 1 + MACHINE_UPGRADE_SLOTS;

    /** Total machine-side inventory slots on a Cargo Station (54 cargo + 1 RF chip + 6 drone bays + 1 battery + 4 upgrades = 66). */
    public static final int CARGO_STATION_MACHINE_SLOTS =
            STATION_TOTAL_CARGO_SLOTS + 1 + CARGO_STATION_DRONE_BAYS + 1 + MACHINE_UPGRADE_SLOTS;

    /**
     * Per-zone cargo buffer slot count on a Smart Port (legacy alias for {@link #STATION_OUTBOUND_SLOTS};
     * prefer {@link #STATION_OUTBOUND_SLOTS}, {@link #STATION_STORAGE_SLOTS}, and {@link #STATION_TOTAL_CARGO_SLOTS}).
     */
    public static final int SMART_PORT_BUFFER_SLOTS = STATION_OUTBOUND_SLOTS;

    /**
     * Per-zone cargo buffer slot count on a Cargo Station (legacy alias for {@link #STATION_OUTBOUND_SLOTS};
     * prefer {@link #STATION_OUTBOUND_SLOTS}, {@link #STATION_STORAGE_SLOTS}, and {@link #STATION_TOTAL_CARGO_SLOTS}).
     */
    public static final int CARGO_STATION_BUFFER_SLOTS = STATION_OUTBOUND_SLOTS;

    /** Cargo inventory slot count on an Electric Smart Cargo Boat (Double Chest size). */
    public static final int BOAT_CARGO_SLOTS = 54;

    /** Cargo inventory slot count on a Standard Transport Drone. */
    public static final int STANDARD_DRONE_CARGO_SLOTS = 12;

    /** Cargo inventory slot count on a Heavy Transport Drone. */
    public static final int HEAVY_DRONE_CARGO_SLOTS = 36;

    /** Base water cruise speed for Electric Smart Cargo Boats (blocks per tick). */
    public static final double BOAT_BASE_SPEED_BLOCKS_PER_TICK = 0.35;

    /** Base air cruise speed for Standard Transport Drones (blocks per tick, 16 b/s). */
    public static final double STANDARD_DRONE_BASE_SPEED_BLOCKS_PER_TICK = 0.80;

    /** Base air cruise speed for Heavy Transport Drones (blocks per tick, 10 b/s). */
    public static final double HEAVY_DRONE_BASE_SPEED_BLOCKS_PER_TICK = 0.50;

    // --- Energy Cost Constants ---

    /** One-way boat dispatch EU cost. */
    public static final double BOAT_ONE_WAY_EU = 3_000.0;

    /** One-way boat dispatch single-use battery cost. */
    public static final int BOAT_ONE_WAY_BATTERIES = 2;

    /** Round-trip (auto-return prepaid) boat dispatch EU cost. */
    public static final double BOAT_ROUND_TRIP_EU = 6_000.0;

    /** Round-trip (auto-return prepaid) boat dispatch single-use battery cost. */
    public static final int BOAT_ROUND_TRIP_BATTERIES = 4;

    /** RF Chip I / Distance Band 1 upper Manhattan distance bound (inclusive). */
    public static final int DRONE_RANGE_BAND_1 = 256;

    /** RF Chip II / Distance Band 2 upper Manhattan distance bound (inclusive). */
    public static final int DRONE_RANGE_BAND_2 = 512;

    /** RF Chip III / Distance Band 3 upper Manhattan distance bound (inclusive). */
    public static final int DRONE_RANGE_BAND_3 = 1024;

    /** Standard drone EU cost for distance <= 256. */
    public static final double DRONE_BAND_1_EU = 800.0;

    /** Standard drone battery cost for distance <= 256. */
    public static final int DRONE_BAND_1_BATTERIES = 1;

    /** Standard drone EU cost for 256 < distance <= 512. */
    public static final double DRONE_BAND_2_EU = 2_000.0;

    /** Standard drone battery cost for 256 < distance <= 512. */
    public static final int DRONE_BAND_2_BATTERIES = 2;

    /** Standard drone EU cost for 512 < distance <= 1024. */
    public static final double DRONE_BAND_3_EU = 7_000.0;

    /** Standard drone battery cost for 512 < distance <= 1024. */
    public static final int DRONE_BAND_3_BATTERIES = 5;

    /** Energy cost multiplier for Heavy Transport Drones relative to Standard Drones. */
    public static final int HEAVY_DRONE_ENERGY_MULTIPLIER = 2;

    // --- Routing & Traffic Constants ---

    /** Default maximum A* node expansions for water pathfinding. */
    public static final int DEFAULT_WATER_PATHFIND_MAX_NODES = 64_000;

    /** Vertical offset within a water block corresponding to boat waterline height. */
    public static final double BOAT_WATER_SURFACE_Y_OFFSET = 0.90;

    /** Base vertical clearance above max(originY, targetY, maxObstacleY) for drone cruise corridors. */
    public static final double AIRSPACE_BASE_ALTITUDE_CLEARANCE = 16.0;

    /** Altitude offset applied to the reverse heading band so opposing flights never share altitude. */
    public static final double AIRSPACE_OPPOSITE_DIRECTION_BAND_OFFSET = 4.0;

    /** Vertical step between concurrent drone airspace lane layers. */
    public static final double AIRSPACE_LANE_LAYER_STEP = 3.0;

    /** Number of distinct concurrent airspace lane layers before wrapping. */
    public static final int AIRSPACE_LANE_LAYER_MODULO = 6;

    /** Minimum longitudinal headway maintained between boats in blocks. */
    public static final double BOAT_MIN_HEADWAY_BLOCKS = 3.0;

    /** Lateral starboard offset applied by boats in wide waterways (>= 3 blocks wide). */
    public static final double BOAT_STARBOARD_OFFSET_BLOCKS = 0.75;

    /** Minimum 3D horizontal/vertical separation threshold triggering drone avoidance. */
    public static final double DRONE_MIN_SEPARATION_BLOCKS = 3.5;

    /** Temporary vertical altitude climb applied by lower-ID drones during 3D avoidance. */
    public static final double DRONE_AVOIDANCE_VERTICAL_OFFSET = 3.0;

    /** Consecutive waiting ticks before deadlock escape logic activates. */
    public static final int DEADLOCK_ESCAPE_TICKS = 40;

    // --- Remote Communications & Operation Terminal Parameters ---

    /** Universal energy conversion ratio: 1 EU equals 4 FE. */
    public static final int FE_PER_EU = 4;

    /** Internal EU buffer capacity of a Communication Base Station (16,000 FE). */
    public static final double COMM_BASE_CAPACITY_EU = 4_000.0;

    /** Electrical input tier of a Communication Base Station (1 = LV, 32 EU/t = 128 FE/t). */
    public static final int COMM_BASE_INPUT_TIER = 1;

    /** Maximum EU/t input packet limit of a Communication Base Station. */
    public static final int COMM_BASE_PACKET_LIMIT_EU = 32;

    /** Standby EU/t consumed by a Communication Base Station to remain online. */
    public static final double COMM_BASE_STANDBY_EU_PER_TICK = 2.0;

    /** Internal EU storage capacity of an Operation Terminal (40,000 FE). */
    public static final double TERMINAL_CAPACITY_EU = 10_000.0;

    /** Electrical charging tier of an Operation Terminal (1 = LV, 32 EU/t = 128 FE/t). */
    public static final int TERMINAL_INPUT_TIER = 1;

    /** Maximum EU/t charging transfer limit of an Operation Terminal. */
    public static final int TERMINAL_PACKET_LIMIT_EU = 32;

    /** EU deducted from the Operation Terminal ONLY upon a successful remote dispatch (400 FE). */
    public static final double TERMINAL_DISPATCH_COST_EU = 100.0;

    /** Defensive cap on the number of Communication Base Station bindings stored on one terminal. */
    public static final int TERMINAL_MAX_BINDINGS = 256;
}
