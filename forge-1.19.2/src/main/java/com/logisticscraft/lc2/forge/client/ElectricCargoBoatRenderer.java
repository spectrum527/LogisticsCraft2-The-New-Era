package com.logisticscraft.lc2.forge.client;

import com.logisticscraft.lc2.forge.util.Lc2Coords;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.model.BoatModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.vehicle.Boat;

/**
 * Renderer for {@link com.logisticscraft.lc2.forge.entity.ElectricCargoBoatEntity} binding
 * vanilla's 128x128 chest-boat {@link BoatModel} ({@code hasChest = true}) to
 * {@code lc2:textures/entity/boat/electric_cargo_boat.png} per {@code docs/DRONE_MODEL_UV.md}.
 */
public class ElectricCargoBoatRenderer extends BoatRenderer {

    private static final ResourceLocation TEXTURE =
            Lc2Coords.id("textures/entity/boat/electric_cargo_boat.png");

    private final BoatModel chestBoatModel;

    public ElectricCargoBoatRenderer(EntityRendererProvider.Context context) {
        super(context, true);
        this.chestBoatModel = new BoatModel(
                context.bakeLayer(ModelLayers.createChestBoatModelName(Boat.Type.OAK)),
                true
        );
    }

    @Override
    public Pair<ResourceLocation, BoatModel> getModelWithLocation(Boat boat) {
        return Pair.of(TEXTURE, this.chestBoatModel);
    }
}
