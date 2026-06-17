package com.capemod.client.cape;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java GIF89a decoder.
 * Produces a list of GifFrame records (ARGB pixel array + delay in ms).
 * No external dependencies required.
 */
public class GifDecoder {

    public record GifFrame(int[] pixels, int width, int height, int delayMs) {}

    // Disposal methods
    private static final int DISPOSAL_UNSPECIFIED  = 0;
    private static final int DISPOSAL_DO_NOT       = 1;
    private static final int DISPOSAL_BACKGROUND   = 2;
    private static final int DISPOSAL_RESTORE_PREV = 3;

    private final DataInputStream in;

    // Logical screen
    private int screenWidth, screenHeight;
    private int[] globalColorTable;
    private int bgColorIndex;

    // Per-frame graphic control extension data
    private int disposalMethod    = DISPOSAL_DO_NOT;
    private boolean hasTransparency = false;
    private int transparentColorIndex = 0;
    private int delayMs           = 100;

    // Canvas state for inter-frame disposal
    private int[] canvas;          // current composed frame (ARGB)
    private int[] prevCanvas;      // saved canvas for RESTORE_PREVIOUS

    private final List<GifFrame> frames = new ArrayList<>();

    private GifDecoder(InputStream raw) {
        this.in = new DataInputStream(new BufferedInputStream(raw));
    }

    public static List<GifFrame> decode(InputStream stream) throws IOException {
        return new GifDecoder(stream).decode();
    }

    // ── entry point ─────────────────────────────────────────────────────────

    private List<GifFrame> decode() throws IOException {
        readHeader();
        readLogicalScreenDescriptor();

        boolean done = false;
        while (!done) {
            int code = in.read();
            switch (code) {
                case 0x2C -> readImageDescriptor();
                case 0x21 -> readExtension();
                case 0x3B -> done = true;          // trailer
                case -1   -> done = true;           // EOF
                default   -> skipBlock();           // unknown – skip
            }
        }
        return frames;
    }

    // ── header ──────────────────────────────────────────────────────────────

    private void readHeader() throws IOException {
        byte[] sig = new byte[6];
        in.readFully(sig);
        String header = new String(sig);
        if (!header.startsWith("GIF")) {
            throw new IOException("Not a GIF file (header: " + header + ")");
        }
    }

    // ── logical screen descriptor ────────────────────────────────────────────

    private void readLogicalScreenDescriptor() throws IOException {
        screenWidth  = readShortLE();
        screenHeight = readShortLE();

        int packed       = in.read();
        boolean gctFlag  = (packed & 0x80) != 0;
        int gctSize      = packed & 0x07;
        /* int colorRes  = (packed >> 4) & 0x07; */  // not needed
        bgColorIndex     = in.read();
        /* int aspect    = */ in.read();             // pixel aspect ratio – ignored

        if (gctFlag) {
            globalColorTable = readColorTable(gctSize);
        }

        canvas     = new int[screenWidth * screenHeight];
        prevCanvas = new int[screenWidth * screenHeight];
        // fill canvas with background colour
        if (globalColorTable != null) {
            int bg = globalColorTable[bgColorIndex];
            for (int i = 0; i < canvas.length; i++) canvas[i] = bg;
        }
    }

    // ── extensions ───────────────────────────────────────────────────────────

    private void readExtension() throws IOException {
        int label = in.read();
        switch (label) {
            case 0xF9 -> readGraphicControlExtension();
            case 0xFF -> readApplicationExtension();
            default   -> skipSubBlocks();
        }
    }

    private void readGraphicControlExtension() throws IOException {
        in.read(); // block size (always 4)
        int packed        = in.read();
        disposalMethod    = (packed >> 2) & 0x07;
        hasTransparency   = (packed & 0x01) != 0;
        delayMs           = readShortLE() * 10;   // centiseconds → ms
        if (delayMs == 0) delayMs = 100;           // some encoders write 0; cap to 100ms
        transparentColorIndex = in.read();
        in.read(); // block terminator
    }

    private void readApplicationExtension() throws IOException {
        // Skip – we don't need Netscape looping info at decode time
        skipSubBlocks();
    }

    // ── image descriptor + LZW data ──────────────────────────────────────────

    private void readImageDescriptor() throws IOException {
        int left   = readShortLE();
        int top    = readShortLE();
        int width  = readShortLE();
        int height = readShortLE();

        int packed     = in.read();
        boolean lctFlag = (packed & 0x80) != 0;
        boolean interlaced = (packed & 0x40) != 0;
        int lctSize    = packed & 0x07;

        int[] colorTable = lctFlag ? readColorTable(lctSize) : globalColorTable;

        int minCodeSize = in.read();
        byte[] lzwData  = readSubBlocks();

        int[] indices   = lzwDecode(lzwData, minCodeSize, width * height);

        // Save canvas for RESTORE_PREVIOUS disposal before we draw
        if (disposalMethod == DISPOSAL_RESTORE_PREV) {
            System.arraycopy(canvas, 0, prevCanvas, 0, canvas.length);
        }

        // Compose onto canvas
        int[] framePixels = new int[screenWidth * screenHeight];
        System.arraycopy(canvas, 0, framePixels, 0, canvas.length);

        int[] deinterlaced = interlaced ? deinterlace(indices, width, height) : indices;

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int idx  = deinterlaced[row * width + col];
                int cx   = left + col;
                int cy   = top + row;
                if (cx >= screenWidth || cy >= screenHeight) continue;
                int pos  = cy * screenWidth + cx;
                if (hasTransparency && idx == transparentColorIndex) {
                    // keep whatever is on canvas already (handle disposal)
                } else {
                    int argb = (colorTable != null && idx < colorTable.length)
                            ? colorTable[idx] : 0xFF000000;
                    framePixels[pos] = argb;
                    canvas[pos]      = argb;
                }
            }
        }

        frames.add(new GifFrame(framePixels, screenWidth, screenHeight, delayMs));

        // Apply disposal for next frame
        switch (disposalMethod) {
            case DISPOSAL_BACKGROUND -> {
                int bg = (globalColorTable != null)
                        ? globalColorTable[bgColorIndex] : 0xFF000000;
                for (int row = 0; row < height; row++) {
                    for (int col = 0; col < width; col++) {
                        int cx = left + col, cy = top + row;
                        if (cx < screenWidth && cy < screenHeight)
                            canvas[cy * screenWidth + cx] = bg;
                    }
                }
            }
            case DISPOSAL_RESTORE_PREV -> System.arraycopy(prevCanvas, 0, canvas, 0, canvas.length);
            // DISPOSAL_DO_NOT / DISPOSAL_UNSPECIFIED – canvas already updated
        }

        // Reset graphic control fields for next frame
        disposalMethod         = DISPOSAL_DO_NOT;
        hasTransparency        = false;
        transparentColorIndex  = 0;
        delayMs                = 100;
    }

    // ── colour table ─────────────────────────────────────────────────────────

    private int[] readColorTable(int sizeField) throws IOException {
        int count  = 2 << sizeField;
        byte[] raw = new byte[count * 3];
        in.readFully(raw);
        int[] table = new int[count];
        for (int i = 0; i < count; i++) {
            int r = raw[i * 3]     & 0xFF;
            int g = raw[i * 3 + 1] & 0xFF;
            int b = raw[i * 3 + 2] & 0xFF;
            table[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return table;
    }

    // ── sub-block helpers ─────────────────────────────────────────────────────

    private byte[] readSubBlocks() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int blockSize;
        while ((blockSize = in.read()) > 0) {
            byte[] block = new byte[blockSize];
            in.readFully(block);
            buf.write(block);
        }
        return buf.toByteArray();
    }

    private void skipSubBlocks() throws IOException {
        int blockSize;
        while ((blockSize = in.read()) > 0) {
            long skipped = 0;
            while (skipped < blockSize) {
                long s = in.skip(blockSize - skipped);
                if (s <= 0) throw new EOFException("Unexpected EOF skipping sub-blocks");
                skipped += s;
            }
        }
    }

    private void skipBlock() throws IOException {
        skipSubBlocks();
    }

    // ── LZW decoder ──────────────────────────────────────────────────────────

    private static int[] lzwDecode(byte[] data, int minCodeSize, int pixelCount) {
        int clearCode = 1 << minCodeSize;
        int eodCode   = clearCode + 1;

        // Build initial code table
        int tableSize = clearCode + 2;
        int[][] table = new int[4096][];
        for (int i = 0; i < clearCode; i++) table[i] = new int[]{i};
        table[clearCode] = new int[0];
        table[eodCode]   = new int[0];

        int[] output   = new int[pixelCount];
        int outputPos  = 0;
        int codeSize   = minCodeSize + 1;
        int bitBuf     = 0;
        int bitCount   = 0;
        int dataPos    = 0;
        int prevCode   = -1;

        while (dataPos < data.length || bitCount >= codeSize) {
            // Fill bit buffer
            while (bitCount < codeSize && dataPos < data.length) {
                bitBuf  |= (data[dataPos++] & 0xFF) << bitCount;
                bitCount += 8;
            }
            if (bitCount < codeSize) break;

            int code = bitBuf & ((1 << codeSize) - 1);
            bitBuf  >>= codeSize;
            bitCount -= codeSize;

            if (code == eodCode) break;

            if (code == clearCode) {
                // Reset
                tableSize = clearCode + 2;
                codeSize  = minCodeSize + 1;
                prevCode  = -1;
                continue;
            }

            int[] entry;
            if (code < tableSize) {
                entry = table[code];
            } else if (code == tableSize && prevCode >= 0) {
                // Special case: code == next table entry
                int[] prev = table[prevCode];
                entry = new int[prev.length + 1];
                System.arraycopy(prev, 0, entry, 0, prev.length);
                entry[prev.length] = prev[0];
            } else {
                break; // corrupt
            }

            // Output pixels
            for (int px : entry) {
                if (outputPos < pixelCount) output[outputPos++] = px;
            }

            // Add to table
            if (prevCode >= 0 && tableSize < 4096) {
                int[] prev = table[prevCode];
                int[] newEntry = new int[prev.length + 1];
                System.arraycopy(prev, 0, newEntry, 0, prev.length);
                newEntry[prev.length] = entry[0];
                table[tableSize++] = newEntry;
                // Grow code size when table fills a power-of-two boundary
                if (tableSize == (1 << codeSize) && codeSize < 12) codeSize++;
            }
            prevCode = code;
        }
        return output;
    }

    // ── interlace helper ─────────────────────────────────────────────────────

    private static int[] deinterlace(int[] src, int width, int height) {
        int[] dst  = new int[width * height];
        int srcRow = 0;
        // GIF interlace passes: start, increment
        int[][] passes = {{0,8},{4,8},{2,4},{1,2}};
        for (int[] pass : passes) {
            for (int row = pass[0]; row < height; row += pass[1]) {
                System.arraycopy(src, srcRow * width, dst, row * width, width);
                srcRow++;
            }
        }
        return dst;
    }

    // ── utility ──────────────────────────────────────────────────────────────

    private int readShortLE() throws IOException {
        int lo = in.read(), hi = in.read();
        return (hi << 8) | lo;
    }
}
