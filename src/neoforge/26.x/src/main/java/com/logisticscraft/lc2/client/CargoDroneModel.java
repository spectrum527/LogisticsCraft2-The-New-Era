package com.logisticscraft.lc2.client;

import com.logisticscraft.lc2.core.LogisticsConstants;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * NeoForge 26.x Cargo Drone EntityModel backed by {@link CargoDroneModelGeometry} (64x64 UV)
 * and {@link RenderState}.
 */
public final class CargoDroneModel extends EntityModel<CargoDroneModel.RenderState> {

    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(LogisticsConstants.MOD_ID, "cargo_drone"),
            "main"
    );

    /** Render state snapshot passed to {@link CargoDroneModel} during 26.x submission. */
    public static final class RenderState extends EntityRenderState {
        public boolean heavy;
        public boolean airborne;
        public float yRot;
        public float ageInTicks;
    }

    private final ModelPart root;
    private final ModelPart rotorFl;
    private final ModelPart rotorFr;
    private final ModelPart rotorBl;
    private final ModelPart rotorBr;
    private final ModelPart sidePodLeft;
    private final ModelPart sidePodRight;

    public CargoDroneModel(ModelPart bakedRoot) {
        super(bakedRoot, RenderTypes::entityCutout);
        this.root = bakedRoot.getChild("root");
        this.rotorFl = this.root.getChild("rotor_fl");
        this.rotorFr = this.root.getChild("rotor_fr");
        this.rotorBl = this.root.getChild("rotor_bl");
        this.rotorBr = this.root.getChild("rotor_br");
        this.sidePodLeft = this.root.getChild("side_pod_left");
        this.sidePodRight = this.root.getChild("side_pod_right");
    }

    @Override
    public void setupAnim(RenderState state) {
        super.setupAnim(state);
        CargoDroneModelGeometry.applyPose(
                rotorFl, rotorFr, rotorBl, rotorBr,
                sidePodLeft, sidePodRight,
                state.heavy, state.airborne, state.ageInTicks
        );
    }
}
