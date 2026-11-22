package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.blockentity.CargoStationBlockEntity;
import com.logisticscraft.lc2.core.AirspaceRouter;
import com.logisticscraft.lc2.core.Vec3d;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * NeoForge 1.21.1 BlockEntityRenderer that renders parked Standard and Heavy Transport Drones
 * cleanly across the 6 roof landing pads of a {@code lc2:cargo_station}.
 */
public final class CargoStationRenderer implements BlockEntityRenderer<CargoStationBlockEntity> {

    private static final float ROOF_DRONE_BASE_SCALE = 0.28F;

    private final CargoDroneModel model;

    public CargoStationRenderer(BlockEntityRendererProvider.Context context) {
        this.model = new CargoDroneModel(context.bakeLayer(CargoDroneModel.LAYER_LOCATION));
    }

    @Override
    public void render(
            CargoStationBlockEntity station,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay
    ) {
        if (station.getLevel() == null) {
            return;
        }
        int roofLight = LevelRenderer.getLightColor(station.getLevel(), station.getBlockPos().above());
        for (int bay = 0; bay < CargoStationBlockEntity.DRONE_BAY_COUNT; bay++) {
            byte kind = station.getClientBayKind(bay);
            if (kind == CargoStationBlockEntity.BAY_EMPTY) {
                continue;
            }
            boolean heavy = kind == CargoStationBlockEntity.BAY_HEAVY;
            float scale = heavy
                    ? ROOF_DRONE_BASE_SCALE * CargoDroneModelGeometry.HEAVY_RENDER_SCALE
                    : ROOF_DRONE_BASE_SCALE;
            Vec3d offset = AirspaceRouter.bayLandingOffset(bay);

            poseStack.pushPose();
            poseStack.translate(offset.x(), 1.0 + 1.501F * scale, offset.z());
            poseStack.scale(-scale, -scale, scale);
            model.configurePose(heavy, false, 0.0F);
            ResourceLocation texture = heavy
                    ? CargoDroneRenderer.HEAVY_TEXTURE
                    : CargoDroneRenderer.STANDARD_TEXTURE;
            VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));
            model.renderToBuffer(poseStack, consumer, roofLight, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
    }
}
