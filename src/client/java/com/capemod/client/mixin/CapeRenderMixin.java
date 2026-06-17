package com.capemod.client.mixin;

import com.capemod.client.cape.GifCapeTexture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.item.Items;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Intercepts AbstractClientPlayerEntity#getSkin() to inject the current GIF
 * frame into the cape slot of SkinTextures only.
 *
 * Elytra is intentionally left untouched (Optional.empty()) so other
 * cosmetics/elytra mods that key off skinTextures.elytra() being null
 * continue to work normally. If the player actually has an elytra equipped
 * in the chest slot, the override is skipped entirely.
 */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class CapeRenderMixin {

    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void gifcape$interceptSkin(CallbackInfoReturnable<SkinTextures> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        AbstractClientPlayerEntity self = (AbstractClientPlayerEntity) (Object) this;
        if (!client.player.getUuid().equals(self.getUuid())) return;

        if (self.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) return;

        SkinTextures original = cir.getReturnValue();
        if (original == null) return;

        GifCapeTexture capeGif = GifCapeTexture.getCapeInstance();
        if (capeGif == null || !capeGif.isReady()) return;

        Identifier capeFrame = capeGif.getCurrentTextureId();
        if (capeFrame == null) return;

        SkinTextures.SkinOverride override = SkinTextures.SkinOverride.create(
                Optional.empty(),                                                   // body  — no change
                Optional.of(new AssetInfo.TextureAssetInfo(capeFrame, capeFrame)),  // cape  — GIF frame
                Optional.empty(),                                                   // elytra — untouched, lets other mods control it
                Optional.empty()                                                    // model — no change
        );

        cir.setReturnValue(original.withOverride(override));
    }
}