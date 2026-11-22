package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.core.AirspaceRouter;
import com.logisticscraft.lc2.core.Vec3d;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * NeoForge 26.x BlockEntityRenderer that renders parked Standard and Heavy Transport Drones
 * cleanly across the 6 roof landing pads of a {@code lc2:cargo_station}.
 */
public final class CargoStationRenderer
        implements BlockEntityRenderer<CargoStationBlockEntity, CargoStationRenderer.StationRenderState> {

    private static final float ROOF_DRONE_BASE_SCALE = 0.28F;

    private final CargoDroneModel model;

    public CargoStationRenderer(BlockEntityRendererProvider.Context context) {
        this.model = new CargoDroneModel(context.bakeLayer(CargoDroneModel.LAYER_LOCATION));
    }

    @Override
    public StationRenderState createRenderState() {
        return new StationRenderState();
    }

    @Override
    public void extractRenderState(
            CargoStationBlockEntity station,
            StationRenderState state,
            float partialTick,
            Vec3 camera,
            CrumblingOverlay crumbling
    ) {
        BlockEntityRenderer.super.extractRenderState(station, state, partialTick, camera, crumbling);
        for (int bay = 0; bay < CargoStationBlockEntity.DRONE_BAY_COUNT; bay++) {
            state.bayKinds[bay] = station.getClientBayKind(bay);
        }
        Level level = station.getLevel();
        if (level != null) {
            BlockPos above = station.getBlockPos().above();
            state.roofLightCoords = LightCoordsUtil.pack(
                    level.getBrightness(LightLayer.BLOCK, above),
                    level.getBrightness(LightLayer.SKY, above)
            );
        } else {
            state.roofLightCoords = state.lightCoords;
        }
    }

    @Override
    public void submit(
            StationRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera
    ) {
        for (int bay = 0; bay < CargoStationBlockEntity.DRONE_BAY_COUNT; bay++) {
            byte kind = state.bayKinds[bay];
            if (kind == CargoStationBlockEntity.BAY_EMPTY) {
                continue;
            }
            boolean heavy = kind == CargoStationBlockEntity.BAY_HEAVY;
            float scale = heavy
                    ? ROOF_DRONE_BASE_SCALE * CargoDroneModelGeometry.HEAVY_RENDER_SCALE
                    : ROOF_DRONE_BASE_SCALE;
            Vec3d offset = AirspaceRouter.bayLandingOffset(bay);

            CargoDroneModel.RenderState droneState = new CargoDroneModel.RenderState();
            droneState.heavy = heavy;
            droneState.airborne = false;
            droneState.ageInTicks = 0.0F;
            droneState.lightCoords = state.roofLightCoords;

            poseStack.pushPose();
            poseStack.translate(offset.x(), 1.0 + 1.501F * scale, offset.z());
            poseStack.scale(-scale, -scale, scale);

            Identifier texture = heavy
                    ? CargoDroneRenderer.HEAVY_TEXTURE
                    : CargoDroneRenderer.STANDARD_TEXTURE;
            collector.submitModel(
                    model,
                    droneState,
                    poseStack,
                    RenderTypes.entityCutout(texture),
                    state.roofLightCoords,
                    OverlayTexture.NO_OVERLAY,
                    0,
                    null
            );
            poseStack.popPose();
        }
    }

    /** Extracted render state for one Cargo Station's 6 roof landing pads. */
    public static final class StationRenderState extends BlockEntityRenderState {
        final byte[] bayKinds = new byte[CargoStationBlockEntity.DRONE_BAY_COUNT];
        int roofLightCoords;
    }
}
