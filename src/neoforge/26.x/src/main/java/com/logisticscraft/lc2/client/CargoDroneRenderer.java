package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.entity.CargoDroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * NeoForge 26.x EntityRenderer for {@link CargoDroneEntity} using {@link SubmitNodeCollector}.
 */
public final class CargoDroneRenderer
        extends EntityRenderer<CargoDroneEntity, CargoDroneModel.RenderState> {

    public static final Identifier STANDARD_TEXTURE = Identifier.fromNamespaceAndPath(
            LogisticsConstants.MOD_ID, "textures/entity/drone/transport_drone.png"
    );
    public static final Identifier HEAVY_TEXTURE = Identifier.fromNamespaceAndPath(
            LogisticsConstants.MOD_ID, "textures/entity/drone/heavy_transport_drone.png"
    );

    private final CargoDroneModel model;

    public CargoDroneRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new CargoDroneModel(context.bakeLayer(CargoDroneModel.LAYER_LOCATION));
        this.shadowRadius = 0.45F;
    }

    @Override
    public CargoDroneModel.RenderState createRenderState() {
        return new CargoDroneModel.RenderState();
    }

    @Override
    public void extractRenderState(
            CargoDroneEntity entity,
            CargoDroneModel.RenderState state,
            float partialTick
    ) {
        super.extractRenderState(entity, state, partialTick);
        state.heavy = entity.isHeavy();
        state.airborne = entity.isAirborne();
        state.yRot = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
        state.ageInTicks = entity.tickCount + partialTick;
    }

    @Override
    public void submit(
            CargoDroneModel.RenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera
    ) {
        poseStack.pushPose();
        float scale = state.heavy ? CargoDroneModelGeometry.HEAVY_RENDER_SCALE : 1.0F;
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - state.yRot));
        poseStack.translate(0.0F, 1.501F * scale, 0.0F);
        poseStack.scale(-scale, -scale, scale);

        Identifier texture = state.heavy ? HEAVY_TEXTURE : STANDARD_TEXTURE;
        collector.submitModel(
                model,
                state,
                poseStack,
                RenderTypes.entityCutout(texture),
                state.lightCoords,
                OverlayTexture.NO_OVERLAY,
                state.outlineColor,
                null
        );
        poseStack.popPose();

        super.submit(state, poseStack, collector, camera);
    }
}
