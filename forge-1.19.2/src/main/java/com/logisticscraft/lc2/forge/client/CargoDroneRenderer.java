package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.forge.entity.CargoDroneEntity;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Vector3f;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Entity renderer for {@link CargoDroneEntity} supporting both {@code STANDARD} and {@code HEAVY}
 * variants with spinning rotors and {@code 1.15F} heavy scaling per {@code docs/DRONE_MODEL_UV.md}.
 */
public class CargoDroneRenderer extends EntityRenderer<CargoDroneEntity> {

    public static final ResourceLocation STANDARD_TEXTURE =
            Lc2Coords.id("textures/entity/drone/transport_drone.png");
    public static final ResourceLocation HEAVY_TEXTURE =
            Lc2Coords.id("textures/entity/drone/heavy_transport_drone.png");

    private final CargoDroneModel model;

    public CargoDroneRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.45F;
        this.model = new CargoDroneModel(context.bakeLayer(CargoDroneModel.LAYER_LOCATION));
    }

    @Override
    public void render(
            CargoDroneEntity entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight
    ) {
        poseStack.pushPose();

        float yaw = Mth.lerp(partialTicks, entity.yRotO, entity.getYRot());
        float scale = entity.isHeavy() ? 1.15F : 1.0F;

        poseStack.mulPose(Vector3f.YP.rotationDegrees(180.0F - yaw));
        poseStack.scale(-scale, -scale, scale);
        poseStack.translate(0.0D, -1.501D, 0.0D);

        float age = entity.tickCount + partialTicks;
        this.model.setupAnim(entity, 0.0F, 0.0F, age, 0.0F, 0.0F);

        ResourceLocation texture = getTextureLocation(entity);
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));
        this.model.renderToBuffer(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(CargoDroneEntity entity) {
        return entity.isHeavy() ? HEAVY_TEXTURE : STANDARD_TEXTURE;
    }
}
