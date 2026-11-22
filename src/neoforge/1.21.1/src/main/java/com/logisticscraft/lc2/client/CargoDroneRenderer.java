package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.entity.CargoDroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * NeoForge 1.21.1 renderer for {@link CargoDroneEntity} supporting both STANDARD and HEAVY variants.
 */
public final class CargoDroneRenderer extends EntityRenderer<CargoDroneEntity> {

    public static final ResourceLocation STANDARD_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            LogisticsConstants.MOD_ID, "textures/entity/drone/transport_drone.png"
    );
    public static final ResourceLocation HEAVY_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            LogisticsConstants.MOD_ID, "textures/entity/drone/heavy_transport_drone.png"
    );

    private final CargoDroneModel model;

    public CargoDroneRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new CargoDroneModel(context.bakeLayer(CargoDroneModel.LAYER_LOCATION));
        this.shadowRadius = 0.45F;
    }

    @Override
    public ResourceLocation getTextureLocation(CargoDroneEntity entity) {
        return entity.isHeavy() ? HEAVY_TEXTURE : STANDARD_TEXTURE;
    }

    @Override
    public void render(
            CargoDroneEntity entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight
    ) {
        poseStack.pushPose();
        boolean heavy = entity.isHeavy();
        float scale = heavy ? CargoDroneModelGeometry.HEAVY_RENDER_SCALE : 1.0F;
        float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        poseStack.translate(0.0F, 1.501F * scale, 0.0F);
        poseStack.scale(-scale, -scale, scale);

        float ageInTicks = entity.tickCount + partialTick;
        model.setupAnim(entity, 0.0F, 0.0F, ageInTicks, 0.0F, 0.0F);

        ResourceLocation texture = getTextureLocation(entity);
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));
        model.renderToBuffer(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();

        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }
}
