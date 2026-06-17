package com.capemod.client.cape;

import com.capemod.client.cape.GifDecoder.GifFrame;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads a GIF from disk, registers one NativeImageBackedTexture per frame,
 * and provides {@link #getCurrentTextureId()} which advances automatically
 * based on wall-clock time.
 *
 * Call {@link #loadCape(Path)} on the client lifecycle thread (CLIENT_STARTED).
 * Call {@link #close()} when the game shuts down.
 */
public class GifCapeTexture {

    private static final Logger LOGGER = LoggerFactory.getLogger("gifcape");

    private static GifCapeTexture capeInstance;

    private final String namespace; // "gifcape_cape"
    private Identifier[] frameIds;
    private long[]       frameDurationsMs;
    private long         totalDurationMs;
    private long         startTimeMs;
    private boolean      ready = false;

    private GifCapeTexture(String namespace) {
        this.namespace = namespace;
    }

    // ── public API ───────────────────────────────────────────────────────────

    public static void loadCape(Path gifPath) {
        capeInstance = load(gifPath, "gifcape_cape");
    }

    public static GifCapeTexture getCapeInstance() { return capeInstance; }

    /**
     * Returns the {@link Identifier} for the frame that should be displayed
     * right now, based on elapsed wall-clock time.
     */
    public Identifier getCurrentTextureId() {
        if (!ready || frameIds == null || frameIds.length == 0) return null;

        long elapsed = (System.currentTimeMillis() - startTimeMs) % totalDurationMs;
        long acc = 0;
        for (int i = 0; i < frameDurationsMs.length; i++) {
            acc += frameDurationsMs[i];
            if (elapsed < acc) return frameIds[i];
        }
        return frameIds[frameIds.length - 1];
    }

    public boolean isReady() { return ready; }

    /** De-registers all GL textures. Call from CLIENT_STOPPING. */
    public void close() {
        if (frameIds == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        for (Identifier id : frameIds) {
            client.getTextureManager().destroyTexture(id);
        }
        ready = false;
    }

    // ── internal ─────────────────────────────────────────────────────────────

    private static GifCapeTexture load(Path gifPath, String namespace) {
        List<GifFrame> frames;
        try (InputStream is = Files.newInputStream(gifPath)) {
            frames = GifDecoder.decode(is);
        } catch (Exception e) {
            LOGGER.error("[GifCape] Failed to decode GIF {}: {}", gifPath.getFileName(), e.getMessage());
            return null;
        }
        if (frames.isEmpty()) {
            LOGGER.warn("[GifCape] GIF {} has no frames — will not be animated.", gifPath.getFileName());
            return null;
        }
        LOGGER.info("[GifCape] Decoded {} frames from {}", frames.size(), gifPath.getFileName());

        GifCapeTexture tex = new GifCapeTexture(namespace);
        tex.register(frames);
        return tex;
    }

    private void register(List<GifFrame> frames) {
        var client = MinecraftClient.getInstance();

        frameIds         = new Identifier[frames.size()];
        frameDurationsMs = new long[frames.size()];
        totalDurationMs  = 0;

        for (int i = 0; i < frames.size(); i++) {
            GifFrame gf = frames.get(i);
            NativeImage img = toNativeImage(gf);

            Identifier id = Identifier.of(namespace, "frame_" + i);

            final int frameIdx = i;
            client.getTextureManager().registerTexture(
                    id,
                    new NativeImageBackedTexture(() -> namespace + "_frame_" + frameIdx, img)
            );

            frameIds[i]         = id;
            frameDurationsMs[i] = Math.max(gf.delayMs(), 20);
            totalDurationMs    += frameDurationsMs[i];
        }

        startTimeMs = System.currentTimeMillis();
        ready       = true;
        LOGGER.info("[GifCape] Registered {} GL textures for {} (loop = {}ms)",
                frames.size(), namespace, totalDurationMs);
    }

    private static NativeImage toNativeImage(GifFrame frame) {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, frame.width(), frame.height(), false);
        int[] pixels = frame.pixels();
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                int argb = pixels[y * frame.width() + x];
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >>  8) & 0xFF;
                int b =  argb        & 0xFF;
                // NativeImage stores ABGR (little-endian RGBA):
                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                img.setColor(x, y, abgr);
            }
        }
        return img;
    }
}
