package com.aziz.stickeratolyesi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** RIFF framing only. Pixel coding/decoding is delegated to Android's WebP codec. */
public final class Webp {
    public static class Chunk {
        public final String tag;
        public final byte[] data;
        Chunk(String t, byte[] d) { tag = t; data = d; }
    }
    public static class Frame {
        public int x, y, width, height, duration, flags;
        public byte[] chunks;
    }
    public static class Info {
        public int width, height, duration, background;
        public boolean animated;
        public final List<Frame> frames = new ArrayList<>();
    }
    public static int le(byte[] d, int p, int n) throws IOException {
        if (p < 0 || p + n > d.length || n < 1 || n > 4) throw new IOException("Eksik WebP başlığı");
        int v = 0;
        for (int i = 0; i < n; i++) v |= (d[p+i] & 255) << (i*8);
        return v;
    }
    public static void le(ByteArrayOutputStream b, int v, int n) {
        for (int i = 0; i < n; i++) b.write((v >>> (i*8)) & 255);
    }
    public static List<Chunk> chunks(byte[] bytes, int offset, int end) throws IOException {
        List<Chunk> result = new ArrayList<>();
        while (offset < end) {
            if (end - offset < 8) throw new IOException("Eksik WebP parçası");
            String tag = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            long size = Integer.toUnsignedLong(le(bytes, offset+4, 4));
            long next = offset + 8L + size + (size & 1);
            if (next > end || size > Integer.MAX_VALUE) throw new IOException("Bozuk WebP uzunluğu");
            result.add(new Chunk(tag, Arrays.copyOfRange(bytes, offset+8, offset+8+(int)size)));
            offset = (int)next;
        }
        return result;
    }
    public static List<Chunk> chunks(byte[] bytes) throws IOException {
        if (bytes.length < 20 || !new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                || !new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")
                || Integer.toUnsignedLong(le(bytes, 4, 4)) + 8 != bytes.length)
            throw new IOException("Geçersiz WebP");
        return chunks(bytes, 12, bytes.length);
    }
    public static Info inspect(byte[] bytes) throws IOException {
        Info i = new Info();
        boolean hasAnimationHeader=false;
        for (Chunk c : chunks(bytes)) {
            byte[] d = c.data;
            if (c.tag.equals("VP8X")) {
                if (d.length != 10) throw new IOException("Geçersiz VP8X");
                i.animated = (d[0] & 2) != 0;
                i.width = le(d, 4, 3)+1; i.height = le(d, 7, 3)+1;
            } else if (c.tag.equals("ANIM")) {
                if (d.length != 6) throw new IOException("Geçersiz ANIM");
                if (hasAnimationHeader) throw new IOException("Birden fazla ANIM başlığı");
                hasAnimationHeader=true;
                i.background = le(d, 0, 4);
            } else if (c.tag.equals("ANMF")) {
                if (d.length < 24) throw new IOException("Eksik animasyon karesi");
                Frame f = new Frame();
                f.x = le(d, 0, 3)*2; f.y = le(d, 3, 3)*2;
                f.width = le(d, 6, 3)+1; f.height = le(d, 9, 3)+1;
                f.duration = le(d, 12, 3); f.flags = d[15] & 255;
                f.chunks = Arrays.copyOfRange(d, 16, d.length);
                chunks(f.chunks, 0, f.chunks.length);
                if ((long)i.duration + f.duration > Integer.MAX_VALUE) throw new IOException("Animasyon çok uzun");
                i.duration += f.duration; i.frames.add(f);
                if (i.frames.size() > 10000) throw new IOException("Çok fazla animasyon karesi");
            } else if (c.tag.equals("VP8 ") && i.width == 0) {
                if (d.length < 10 || (d[3]&255) != 0x9d || d[4] != 1 || d[5] != 0x2a)
                    throw new IOException("Geçersiz VP8");
                i.width = le(d, 6, 2) & 0x3fff; i.height = le(d, 8, 2) & 0x3fff;
            } else if (c.tag.equals("VP8L") && i.width == 0) {
                if (d.length < 5 || d[0] != 0x2f) throw new IOException("Geçersiz VP8L");
                int bits = le(d, 1, 4);
                i.width = (bits & 0x3fff)+1; i.height = ((bits >>> 14) & 0x3fff)+1;
            }
        }
        if (i.width < 1 || i.height < 1 || i.animated != !i.frames.isEmpty() || (i.animated&&!hasAnimationHeader)) throw new IOException("Geçersiz WebP boyutu/animasyonu");
        for (Frame f : i.frames)
            if ((long)f.x+f.width > i.width || (long)f.y+f.height > i.height || (f.flags & ~3) != 0)
                throw new IOException("Kare tuvalin dışında");
        return i;
    }
    public static boolean stickerCompatible(byte[] b, Info i) {
        if (i.width != 512 || i.height != 512 || b.length > (i.animated ? 500*1024 : 100*1024)) return false;
        if (!i.animated) return true;
        if (i.frames.size() < 2 || i.duration <= 0 || i.duration > 10000) return false;
        for (Frame f : i.frames) if (f.duration < 8) return false;
        return true;
    }
    public static void chunk(ByteArrayOutputStream b, String tag, byte[] data) throws IOException {
        b.write(tag.getBytes(StandardCharsets.US_ASCII)); le(b, data.length, 4); b.write(data);
        if ((data.length & 1) != 0) b.write(0);
    }
    public static byte[] riff(byte[] body) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write("RIFF".getBytes(StandardCharsets.US_ASCII)); le(b, body.length+4, 4);
        b.write("WEBP".getBytes(StandardCharsets.US_ASCII)); b.write(body); return b.toByteArray();
    }
    private static byte[] extended(int width, int height, int flags) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        le(b, flags, 4); le(b, width-1, 3); le(b, height-1, 3); return b.toByteArray();
    }
    public static byte[] standalone(Frame f) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        chunk(b, "VP8X", extended(f.width, f.height, 0x10)); b.write(f.chunks); return riff(b.toByteArray());
    }
    public static final class Animation {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int duration, count;
        public Animation() throws IOException {
            chunk(body, "VP8X", extended(512, 512, 0x12));
            chunk(body, "ANIM", new byte[6]); // transparent background, infinite loop
        }
        public void add(byte[] still, int ms) throws IOException {
            Info i = inspect(still);
            if (i.animated || i.width != 512 || i.height != 512 || ms < 8 || duration+ms > 10000)
                throw new IOException("Geçersiz çıkartma karesi");
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            le(f, 0, 3); le(f, 0, 3); le(f, 511, 3); le(f, 511, 3); le(f, ms, 3);
            f.write(2); // full canvas, no blending, retain until replaced
            boolean coded = false;
            for (Chunk c : chunks(still)) {
                if (c.tag.equals("ALPH") || c.tag.equals("VP8 ") || c.tag.equals("VP8L")) {
                    chunk(f, c.tag, c.data);
                    if (!c.tag.equals("ALPH")) coded = true;
                }
            }
            if (!coded) throw new IOException("Kare piksel verisi bulunamadı");
            chunk(body, "ANMF", f.toByteArray()); count++; duration += ms;
        }
        public int size() { return body.size()+12; }
        public byte[] finish() throws IOException {
            if (count < 2) throw new IOException("Animasyon en az iki kare gerektirir");
            return riff(body.toByteArray());
        }
    }
}
