# 物流时代2：新纪元 (LogisticsCraft²: The New Era)

**简体中文** · [English](README.en.md)

**物流时代2：新纪元（`lc2`）** 是 [工业时代2：新纪元 (IndustrialCraft²: The New Era)](https://github.com/spectrum527/IndustrialCraft2-The-New-Era) 的独立物流附属模组，为主模组带来跨未加载区块运行的水路电动智能运输船与空路货运无人机系统。

## 下载与安装

前置要求：请先安装对应 Minecraft 版本的 **IndustrialCraft²: The New Era (`ic2` v1.0.5+)**。将对应版本的 `lc2` JAR 放入 `mods/` 目录，联机时客户端与服务端均需安装。

[v1.0.1 发行说明](https://github.com/spectrum527/LogisticsCraft2-The-New-Era/releases/tag/v1.0.1) · [SHA-256 校验](https://github.com/spectrum527/LogisticsCraft2-The-New-Era/releases/download/v1.0.1/SHA256SUMS.txt)

| Minecraft | 加载器 | Java | 下载 |
| --- | --- | ---: | --- |
| 1.19.2 | Forge 43.5.0 | 17 | [Forge 1.19.2 JAR](https://github.com/spectrum527/LogisticsCraft2-The-New-Era/releases/download/v1.0.1/lc2-forge-1.19.2-1.0.1.jar) |
| 1.21.1 | NeoForge 21.1.265 | 21 | [NeoForge 1.21.1 JAR](https://github.com/spectrum527/LogisticsCraft2-The-New-Era/releases/download/v1.0.1/lc2-neoforge-1.21.1-1.0.1.jar) |
| 26.1.2 | NeoForge 26.1.2.128 | 25 | [NeoForge 26.1.2 JAR](https://github.com/spectrum527/LogisticsCraft2-The-New-Era/releases/download/v1.0.1/lc2-neoforge-26.1.2-1.0.1.jar) |
| 26.2 | NeoForge 26.2.0.95 | 25 | [NeoForge 26.2 JAR](https://github.com/spectrum527/LogisticsCraft2-The-New-Era/releases/download/v1.0.1/lc2-neoforge-26.2-1.0.1.jar) |

## 核心玩法

### 水路：智能港口与电动智能运输船
- **智能港口（`lc2:smart_port`）**：放置在水边（正面朝向水域），内置 27 格缓冲槽，并自动连接相邻的箱子、大箱子、潜影盒、IC2 储物箱及兼容容器。
- **电动智能运输船（`lc2:electric_cargo_boat`）**：拥有 **54 格**（大箱子）货舱容量。停靠待命时玩家可潜行右键查看货舱或直接右键登船；**自动运行途中禁止登船，但可先登船再触发红石发车随船航行**。
- **启动与能耗**：确认两港口间**水域连通**并收到红石信号后发车（可 GUI 命名、列表选择同维度目标或手动填坐标，支持自动返回开关）。
  - 电容量 **14,400 EU**（默认 LV 32 EU/t 输入），另设独立**一次性电池槽**（仅接受 `ic2:single_use_battery`）及 GUI **供电优先级开关**（优先 EU / 优先电池，不足时自动切换，不混扣）。
  - 单程消耗 **3,000 EU** 或 **2 个一次性电池**；开启自动返回时往返翻倍（**6,000 EU** 或 **4 个一次性电池**）。
- **水路避撞与航路**：A* 水道连通检测与航点缓存，宽航道右侧通行偏移 + 窄航道让行防死锁。

### 空路：货运基站与运输无人机
- **货运基站（`lc2:cargo_station`）**：拥有 **6 个无人机停机位**（顶面可视化停靠渲染）与 18 格缓冲槽，自动连接相邻容器；目标基站无空位时拒绝起飞（发车时原子预占目标机位）。
- **两种无人机**：
  - **运输无人机（`lc2:transport_drone`）**：高速飞行（`0.80` 格/tick），**12 格**货舱。
  - **重型运输无人机（`lc2:heavy_transport_drone`）**：稳重重型载具（`0.50` 格/tick），**36 格**货舱，能耗翻倍。
- **射频芯片（`lc2:rf_chip_1` / `rf_chip_2` / `rf_chip_3`）**：基站必须安装射频芯片才可工作，分别支持 **256 / 512 / 1024** 曼哈顿距离上限。
- **按实际距离分档计费**（电容量 **14,400 EU**，支持一次性电池槽与供电优先级开关）：
  - ≤ 256 格：**800 EU** 或 **1** 一次性电池（重型 **1,600 EU** / **2** 电池）
  - 257–512 格：**2,000 EU** 或 **2** 一次性电池（重型 **4,000 EU** / **4** 电池）
  - 513–1024 格：**7,000 EU** 或 **5** 一次性电池（重型 **14,000 EU** / **10** 电池）
  - 开启**自动返回**时，去程由出发站扣费，卸货后返程由目标站扣费（目标站能源不足则在机位待命，补电后自动返航）。
- **空域分层与避撞**：垂直起降（VTOL）+ 去/返程及并发航线垂直分层巡航 + 3D 动态避让。

### 未加载区块运行、区块加载升级与 IC2 升级联动
- **跨未加载区块虚拟运行**：所有运输船与无人机在离开已加载区块后自动转入服务端虚拟航行模拟（无需加载沿途区块），进入已加载区块时无缝物化为实体并完整支持重进存档恢复。
- **区块加载升级（`lc2:chunk_loading_upgrade`）**：装入港口或基站机器升级槽后，通电时消耗 **2 EU/t** 维持自身所在 **3×3 区块**强加载，确保无人值守时持续发车与装卸货；断电或取下立即释放区块票据。
- **完整 IC2 联动**：直连 IC2 EU 导线（视觉自动连接）并兼容 The One Probe、扳手以及 IC2 全部适用升级（高压升级、储能升级、超频升级、弹出/高级弹出升级、抽入/高级抽入升级、红石信号反转升级）。

## 从源码构建

将对应版本的 IC2 v1.0.5 JAR 放入 `.ic2-deps/` 后执行：

```sh
# 构建核心单测与 NeoForge 1.21.1 / 26.1.2 / 26.2 发行包
./gradlew :checkCommonImports :test:test :neoforge:1.21.1:jar :neoforge:26.1.2:jar :neoforge:26.2:jar

# 构建 Forge 1.19.2 重混淆发行包（Java 17）
./forge-1.19.2/gradlew -p forge-1.19.2 build reobfJar
```

详细工程设计与开发文档见 [docs/DESIGN.md](docs/DESIGN.md)、[docs/GUI_LAYOUT.md](docs/GUI_LAYOUT.md) 与 [docs/DRONE_MODEL_UV.md](docs/DRONE_MODEL_UV.md)。

## 许可证

本项目代码采用 [GPL-3.0-only](LICENSE) 开源许可，原创像素美术采用 CC BY-SA 4.0 许可。
