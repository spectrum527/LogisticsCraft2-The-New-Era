package com.logisticscraft.lc2.client;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * Shared 64x64 {@link LayerDefinition} and animation helper for {@code lc2:cargo_drone},
 * matching every single part name, box, pivot, and UV coordinate in {@code docs/DRONE_MODEL_UV.md}.
 */
public final class CargoDroneModelGeometry {

    public static final float HEAVY_RENDER_SCALE = 1.15F;

    private CargoDroneModelGeometry() {
    }

    /** Creates the exact 64x64 CargoDroneModel LayerDefinition specified in {@code docs/DRONE_MODEL_UV.md}. */
    public static LayerDefinition createLayerDefinition() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition rootPart = mesh.getRoot();

        PartDefinition root = rootPart.addOrReplaceChild(
                "root",
                CubeListBuilder.create(),
                PartPose.offset(0.0F, 17.0F, 0.0F)
        );

        // Central chassis, front optical/RF sensor, and rear vertical antenna mast
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

        // Four diagonal rotor booms
        CubeListBuilder armBox = CubeListBuilder.create().texOffs(40, 4).addBox(-1.0F, -0.5F, -7.5F, 2.0F, 1.0F, 6.0F);
        root.addOrReplaceChild("arm_fl", armBox, PartPose.rotation(0.0F, +0.785398F, 0.0F));
        root.addOrReplaceChild("arm_fr", armBox, PartPose.rotation(0.0F, -0.785398F, 0.0F));
        root.addOrReplaceChild("arm_bl", armBox, PartPose.rotation(0.0F, +2.356194F, 0.0F));
        root.addOrReplaceChild("arm_br", armBox, PartPose.rotation(0.0F, -2.356194F, 0.0F));

        // Four motor nacelles
        CubeListBuilder motorBox = CubeListBuilder.create().texOffs(32, 14).addBox(-1.0F, -1.5F, -1.0F, 2.0F, 3.0F, 2.0F);
        root.addOrReplaceChild("motor_fl", motorBox, PartPose.offset(-5.5F, -0.5F, -5.5F));
        root.addOrReplaceChild("motor_fr", motorBox, PartPose.offset(+5.5F, -0.5F, -5.5F));
        root.addOrReplaceChild("motor_bl", motorBox, PartPose.offset(-5.5F, -0.5F, +5.5F));
        root.addOrReplaceChild("motor_br", motorBox, PartPose.offset(+5.5F, -0.5F, +5.5F));

        // Four counter-rotating crossed rotor blades
        CubeListBuilder rotorBlades = CubeListBuilder.create()
                .texOffs(40, 14).addBox(-4.0F, -0.5F, -0.5F, 8.0F, 1.0F, 1.0F)
                .texOffs(40, 16).addBox(-0.5F, -0.5F, -4.0F, 1.0F, 1.0F, 8.0F);
        root.addOrReplaceChild("rotor_fl", rotorBlades, PartPose.offset(-5.5F, -2.5F, -5.5F));
        root.addOrReplaceChild("rotor_fr", rotorBlades, PartPose.offset(+5.5F, -2.5F, -5.5F));
        root.addOrReplaceChild("rotor_bl", rotorBlades, PartPose.offset(-5.5F, -2.5F, +5.5F));
        root.addOrReplaceChild("rotor_br", rotorBlades, PartPose.offset(+5.5F, -2.5F, +5.5F));

        // Under-slung cargo crate and landing skids
        root.addOrReplaceChild(
                "cargo_bay",
                CubeListBuilder.create().texOffs(0, 14).addBox(-4.0F, 2.0F, -4.0F, 8.0F, 5.0F, 8.0F),
                PartPose.ZERO
        );
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

        // Heavy-lift auxiliary side cargo pods (visible only when isHeavy == true)
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

    /** Applies rotor spin and heavy side-pod visibility to a baked CargoDroneModel part hierarchy. */
    public static void applyPose(
            ModelPart rotorFl,
            ModelPart rotorFr,
            ModelPart rotorBl,
            ModelPart rotorBr,
            ModelPart sidePodLeft,
            ModelPart sidePodRight,
            boolean isHeavy,
            boolean isAirborne,
            float ageInTicks
    ) {
        float spin = isAirborne ? ageInTicks * 0.9F : 0.0F;
        rotorFl.yRot = +spin;
        rotorFr.yRot = -spin;
        rotorBl.yRot = -spin;
        rotorBr.yRot = +spin;
        sidePodLeft.visible = isHeavy;
        sidePodRight.visible = isHeavy;
    }
}
