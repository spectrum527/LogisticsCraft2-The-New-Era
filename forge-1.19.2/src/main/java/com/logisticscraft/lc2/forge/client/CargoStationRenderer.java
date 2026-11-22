package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.core.AirspaceRouter;
import com.logisticscraft.lc2.core.Vec3d;
import com.logisticscraft.lc2.core.VehicleKind;
import com.logisticscraft.lc2.forge.block.CargoStationBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Block entity renderer displaying parked Standard and Heavy Transport Drones on the 6 roof
 * landing pads of a {@link CargoStationBlockEntity} using {@link CargoDroneModel}.
 */
public class CargoStationRenderer implements BlockEntityRenderer<CargoStationBlockEntity> {

    private final CargoDroneModel droneModel;

    public CargoStationRenderer(BlockEntityRendererProvider.Context context) {
        this.droneModel = new CargoDroneModel(context.bakeLayer(CargoDroneModel.LAYER_LOCATION));
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
        for (int bay = 0; bay < CargoStationBlockEntity.DRONE_BAY_COUNT; bay++) {
            VehicleKind kind = station.getBayDroneKind(bay);
            if (kind == null) {
                continue;
            }
            boolean heavy = kind == VehicleKind.HEAVY_DRONE;
            Vec3d offset = AirspaceRouter.bayLandingOffset(bay);

            poseStack.pushPose();
            // Position miniature drone neatly on its 3x2 landing pad atop the 1x1 block roof
            poseStack.translate(offset.x(), 1.005D, offset.z());
            float scale = heavy ? 0.30F : 0.26F;
            poseStack.scale(-scale, -scale, scale);
            poseStack.translate(0.0D, -1.5D, 0.0D);

            this.droneModel.configure(heavy, 0.0F);
            ResourceLocation tex = heavy ? CargoDroneRenderer.HEAVY_TEXTURE : CargoDroneRenderer.STANDARD_TEXTURE;
            VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(tex));
            this.droneModel.renderToBuffer(
                    poseStack,
                    consumer,
                    packedLight,
                    OverlayTexture.NO_OVERLAY,
                    1.0F,
                    1.0F,
                    1.0F,
                    1.0F
            );
            poseStack.popPose();
        }
    }
}
