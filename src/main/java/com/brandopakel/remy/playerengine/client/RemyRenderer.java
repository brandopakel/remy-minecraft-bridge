package com.brandopakel.remy.playerengine.client;

import com.brandopakel.remy.playerengine.RemyEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeadFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

/** Renders Remy with the vanilla player model, armor and held items (default skin for now). */
public class RemyRenderer extends LivingEntityRenderer<RemyEntity, PlayerEntityModel<RemyEntity>> {
    public RemyRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
        this.addFeature(new ArmorFeatureRenderer<>(this,
                new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
                new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
        this.addFeature(new HeldItemFeatureRenderer<>(this, ctx.getHeldItemRenderer()));
        this.addFeature(new HeadFeatureRenderer<>(this, ctx.getModelLoader(), ctx.getHeldItemRenderer()));
    }

    @Override
    public void render(RemyEntity remy, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        PlayerEntityModel<RemyEntity> model = this.getModel();
        model.setVisible(true);
        model.sneaking = remy.isInSneakingPose();
        model.rightArmPose = remy.getStackInHand(Hand.MAIN_HAND).isEmpty()
                ? BipedEntityModel.ArmPose.EMPTY : BipedEntityModel.ArmPose.ITEM;
        model.leftArmPose = remy.getStackInHand(Hand.OFF_HAND).isEmpty()
                ? BipedEntityModel.ArmPose.EMPTY : BipedEntityModel.ArmPose.ITEM;
        super.render(remy, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    @Override
    protected void scale(RemyEntity remy, MatrixStack matrices, float amount) {
        matrices.scale(0.9375f, 0.9375f, 0.9375f);
    }

    @Override
    public Identifier getTexture(RemyEntity remy) {
        return DefaultSkinHelper.getTexture(remy.getUuid());
    }
}
