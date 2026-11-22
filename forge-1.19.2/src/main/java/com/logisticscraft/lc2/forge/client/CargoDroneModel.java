package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.forge.entity.CargoDroneEntity;
import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * Custom 64x64 entity/block-entity model for Standard and Heavy Cargo Drones matching
 * {@code docs/DRONE_MODEL_UV.md} pixel-for-pixel.
 */
public class CargoDroneModel extends EntityModel<CargoDroneEntity> {

    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(Lc2Coords.id("cargo_drone"), "main");

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

    /**
     * Builds the exact 64x64 {@link LayerDefinition} specified in {@code docs/DRONE_MODEL_UV.md}.
     */
    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition partDef = mesh.getRoot();

        PartDefinition root = partDef.addOrReplaceChild(
                "root",
                CubeListBuilder.create(),
                PartPose.offset(0.0F, 17.0F, 0.0F)
        );

        root.addOrReplaceChild(
                "body",
                CubeListBuilder.create().texOffs(0, 0).addBox(-5.0F, -2.0F, -5.0F, 10.0F, 4.0F, 10.0F),
                PartPose.ZERO
        );
        root.addOrReplaceChild(
                "nose_sensor",
                CubeListBuilder.create().texOffs(40, 0).addBox(-2.0F, -1.0F, -7.0F, 4.0F, 2.0F, 2.0F),
                PartPose.ZERO
        );
        root.addOrReplaceChild(
                "antenna",
                CubeListBuilder.create().texOffs(58, 0).addBox(-0.5F, -6.0F, 3.0F, 1.0F, 4.0F, 1.0F),
                PartPose.ZERO
        );

        // Diagonal arms
        CubeListBuilder armCube = CubeListBuilder.create().texOffs(40, 4).addBox(-1.0F, -0.5F, -7.5F, 2.0F, 1.0F, 6.0F);
        root.addOrReplaceChild("arm_fl", armCube, PartPose.rotation(0.0F, +0.785398F, 0.0F));
        root.addOrReplaceChild("arm_fr", armCube, PartPose.rotation(0.0F, -0.785398F, 0.0F));
        root.addOrReplaceChild("arm_bl", armCube, PartPose.rotation(0.0F, +2.356194F, 0.0F));
        root.addOrReplaceChild("arm_br", armCube, PartPose.rotation(0.0F, -2.356194F, 0.0F));

        // Motor nacelles
        CubeListBuilder motorCube = CubeListBuilder.create().texOffs(32, 14).addBox(-1.0F, -1.5F, -1.0F, 2.0F, 3.0F, 2.0F);
        root.addOrReplaceChild("motor_fl", motorCube, PartPose.offset(-5.5F, -0.5F, -5.5F));
        root.addOrReplaceChild("motor_fr", motorCube, PartPose.offset(+5.5F, -0.5F, -5.5F));
        root.addOrReplaceChild("motor_bl", motorCube, PartPose.offset(-5.5F, -0.5F, +5.5F));
        root.addOrReplaceChild("motor_br", motorCube, PartPose.offset(+5.5F, -0.5F, +5.5F));

        // Crossed rotor blades
        CubeListBuilder rotorBlades = CubeListBuilder.create()
                .texOffs(40, 14).addBox(-4.0F, -0.5F, -0.5F, 8.0F, 1.0F, 1.0F)
                .texOffs(40, 16).addBox(-0.5F, -0.5F, -4.0F, 1.0F, 1.0F, 8.0F);
        root.addOrReplaceChild("rotor_fl", rotorBlades, PartPose.offset(-5.5F, -2.5F, -5.5F));
        root.addOrReplaceChild("rotor_fr", rotorBlades, PartPose.offset(+5.5F, -2.5F, -5.5F));
        root.addOrReplaceChild("rotor_bl", rotorBlades, PartPose.offset(-5.5F, -2.5F, +5.5F));
        root.addOrReplaceChild("rotor_br", rotorBlades, PartPose.offset(+5.5F, -2.5F, +5.5F));

        // Central under-slung cargo crate
        root.addOrReplaceChild(
                "cargo_bay",
                CubeListBuilder.create().texOffs(0, 14).addBox(-4.0F, 2.0F, -4.0F, 8.0F, 5.0F, 8.0F),
                PartPose.ZERO
        );

        // Landing skids
        root.addOrReplaceChild(
                "skid_left",
                CubeListBuilder.create().texOffs(0, 27).addBox(-5.0F, 6.0F, -5.0F, 1.0F, 1.0F, 10.0F),
                PartPose.ZERO
        );
        root.addOrReplaceChild(
                "skid_right",
                CubeListBuilder.create().texOffs(0, 27).addBox(4.0F, 6.0F, -5.0F, 1.0F, 1.0F, 10.0F),
                PartPose.ZERO
        );

        // Heavy side cargo pods (visible only when HEAVY variant)
        root.addOrReplaceChild(
                "side_pod_left",
                CubeListBuilder.create().texOffs(22, 27).addBox(-7.0F, 2.0F, -4.0F, 3.0F, 4.0F, 8.0F),
                PartPose.ZERO
        );
        root.addOrReplaceChild(
                "side_pod_right",
                CubeListBuilder.create().texOffs(22, 27).addBox(4.0F, 2.0F, -4.0F, 3.0F, 4.0F, 8.0F),
                PartPose.ZERO
        );

        return LayerDefinition.create(mesh, 64, 64);
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
        float spin = entity.isAirborne() ? ageInTicks * 0.9F : ageInTicks * 0.05F;
        configure(entity.isHeavy(), spin);
    }

    /**
     * Configures rotor angle and heavy side-pod visibility for both entity rendering and
     * docked roof rendering in {@link CargoStationRenderer}.
     */
    public void configure(boolean heavy, float rotorSpinRadians) {
        this.rotorFl.yRot = +rotorSpinRadians;
        this.rotorFr.yRot = -rotorSpinRadians;
        this.rotorBl.yRot = -rotorSpinRadians;
        this.rotorBr.yRot = +rotorSpinRadians;
        this.sidePodLeft.visible = heavy;
        this.sidePodRight.visible = heavy;
    }

    @Override
    public void renderToBuffer(
            PoseStack poseStack,
            VertexConsumer buffer,
            int packedLight,
            int packedOverlay,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        this.root.render(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
