package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.core.LogisticsConstants;
import com.logisticscraft.lc2.entity.CargoDroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;

/**
 * NeoForge 1.21.1 Cargo Drone EntityModel backed by {@link CargoDroneModelGeometry} (64x64 UV).
 */
public final class CargoDroneModel extends EntityModel<CargoDroneEntity> {

    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(LogisticsConstants.MOD_ID, "cargo_drone"),
            "main"
    );

    private final ModelPart root;
    private final ModelPart rotorFl;
    private final ModelPart rotorFr;
    private final ModelPart rotorBl;
    private final ModelPart rotorBr;
    private final ModelPart sidePodLeft;
    private final ModelPart sidePodRight;

    public CargoDroneModel(ModelPart bakedRoot) {
        this.root = bakedRoot.getChild("root");
        this.rotorFl = this.root.getChild("rotor_fl");
        this.rotorFr = this.root.getChild("rotor_fr");
        this.rotorBl = this.root.getChild("rotor_bl");
        this.rotorBr = this.root.getChild("rotor_br");
        this.sidePodLeft = this.root.getChild("side_pod_left");
        this.sidePodRight = this.root.getChild("side_pod_right");
    }

    @Override
    public void setupAnim(
            CargoDroneEntity entity,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch
    ) {
        configurePose(entity.isHeavy(), entity.isAirborne(), ageInTicks);
    }

    /** Configures rotor animation and heavy side-pod visibility for both entity and roof rendering. */
    public void configurePose(boolean isHeavy, boolean isAirborne, float ageInTicks) {
        CargoDroneModelGeometry.applyPose(
                rotorFl, rotorFr, rotorBl, rotorBr,
                sidePodLeft, sidePodRight,
                isHeavy, isAirborne, ageInTicks
        );
    }

    @Override
    public void renderToBuffer(
            PoseStack poseStack,
            VertexConsumer vertexConsumer,
            int packedLight,
            int packedOverlay,
            int color
    ) {
        root.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
    }
}
