package com.brandopakel.remy.playerengine.client;

import com.brandopakel.remy.playerengine.RemyPlayerEngineAdapter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.ZombieEntityRenderer;

public final class RemyPlayerEngineAdapterClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(RemyPlayerEngineAdapter.REMY, ZombieEntityRenderer::new);
    }
}
