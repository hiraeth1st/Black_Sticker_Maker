package com.aziz.stickeratolyesi;

import android.graphics.*;
import android.media.MediaMetadataRetriever;
import java.io.*;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.CancellationException;

public final class Converter {
    public interface Check { void check() throws CancellationException; }
    public static class Result {
        public byte[] bytes;
        public boolean animated;
        public int duration;
        public String note = "";
    }
    private interface Frames extends AutoCloseable {
        int duration();
        Bitmap at(int ms) throws Exception;
        void reset() throws Exception;
        void close() throws Exception;
    }
    private static final Paint FILTER = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Converter() {}
    public static Result convert(File file, String name, Check check) throws Exception {
        check.check();
        String ext = name.toLowerCase(Locale.ROOT);
        byte[] header = new byte[12];
        try (InputStream in = new FileInputStream(file)) { in.read(header); }
        boolean webp = header[0]=='R' && header[1]=='I' && header[8]=='W' && header[9]=='E';
        boolean gif = header[0]=='G' && header[1]=='I' && header[2]=='F';
        if (webp) {
            if (file.length() > 32L*1024*1024) throw new IOException("WebP 32 MB sınırını aşıyor");
            byte[] data = Files.readAllBytes(file.toPath());
            Webp.Info info = Webp.inspect(data);
            if (Webp.stickerCompatible(data, info)) {
                // Decode once as well: RIFF validation alone cannot detect a corrupt VP8 bitstream.
                if (info.animated) {
                    try (WebpFrames frames = new WebpFrames(info)) {
                        for (int t = 0; t < info.duration; t += 100) {
                            check.check(); Bitmap b = frames.at(t); b.recycle();
                        }
                        Bitmap b = frames.at(info.duration-1); b.recycle();
                    }
                } else { Bitmap b = image(file); b.recycle(); }
                Result r = new Result(); r.bytes=data; r.animated=info.animated; r.duration=info.duration; return r;
            }
            if (info.animated) return animation(new WebpFrames(info), check);
        }
        if (gif) {
            if (file.length() > 24L*1024*1024) throw new IOException("GIF 24 MB sınırını aşıyor");
            int width = (header[6]&255) | ((header[7]&255)<<8);
            int height = (header[8]&255) | ((header[9]&255)<<8);
            if ((long)width*height > 16_000_000) throw new IOException("GIF çözünürlüğü çok büyük");
            Movie movie = Movie.decodeFile(file.getAbsolutePath());
            if (movie == null) throw new IOException("GIF okunamadı");
            if (movie.duration() > 0) return animation(new GifFrames(movie), check);
        }
        if (ext.matches(".*\\.(mp4|m4v|mov|webm|mkv|3gp)$")) return animation(new VideoFrames(file), check);
        Bitmap original = image(file);
        try {
            Result r = new Result();
            for (int quality : new int[]{92,80,65,48,32,18}) {
                check.check(); r.bytes=encode(original, quality);
                if (r.bytes.length <= 100*1024) return r;
            }
            for (int detail : new int[]{384,256,192}) {
                Bitmap small = Bitmap.createScaledBitmap(original, detail, detail, true);
                Bitmap soft = Bitmap.createScaledBitmap(small, 512, 512, true);
                small.recycle(); r.bytes=encode(soft,30); soft.recycle();
                if (r.bytes.length<=100*1024) { r.note="Dosya boyutu için ayrıntı azaltıldı"; return r; }
            }
            throw new IOException("Görsel 100 KB sınırına indirilemedi");
        } finally { original.recycle(); }
    }
    static Bitmap image(File file) throws IOException {
        Bitmap decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w=info.getSize().getWidth(), h=info.getSize().getHeight();
            if (w<1 || h<1) throw new IllegalArgumentException("Geçersiz boyut");
            float ratio = Math.min(1f, 512f/Math.max(w,h));
            decoder.setTargetSize(Math.max(1,Math.round(w*ratio)),Math.max(1,Math.round(h*ratio)));
        });
        Bitmap square = square(decoded); decoded.recycle(); return square;
    }
    static Bitmap square(Bitmap src) {
        Bitmap out=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);
        float scale=Math.min(512f/src.getWidth(),512f/src.getHeight());
        float w=src.getWidth()*scale, h=src.getHeight()*scale;
        new Canvas(out).drawBitmap(src,null,new RectF((512-w)/2,(512-h)/2,(512+w)/2,(512+h)/2),FILTER);
        return out;
    }
    @SuppressWarnings("deprecation")
    static byte[] encode(Bitmap bitmap, int quality) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.WEBP,quality,out)) throw new IOException("WebP kodlanamadı");
        return out.toByteArray();
    }
    static void tray(File sticker, File destination) throws IOException {
        Bitmap b=image(sticker);
        Bitmap tiny=Bitmap.createScaledBitmap(b,96,96,true); b.recycle();
        try (OutputStream out=new FileOutputStream(destination)) {
            if (!tiny.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IOException("Paket simgesi kaydedilemedi");
        } finally { tiny.recycle(); }
        if (destination.length()>50*1024) throw new IOException("Paket simgesi çok büyük");
    }
    private static Result animation(Frames source, Check check) throws Exception {
        try (Frames frames=source) {
            int duration=Math.min(10000,Math.max(80,frames.duration()));
            int[] fps={12,10,8,6,5,4};
            int[] quality={78,64,50,38,28,20};
            for (int attempt=0;attempt<fps.length;attempt++) {
                check.check(); frames.reset();
                int count=Math.max(2,(int)Math.ceil(duration*fps[attempt]/1000.0));
                Webp.Animation animation=new Webp.Animation();
                boolean tooLarge=false;
                for (int n=0;n<count;n++) {
                    check.check();
                    int start=n*duration/count, end=(n+1)*duration/count;
                    Bitmap bitmap=frames.at(Math.min(start,Math.max(0,frames.duration()-1)));
                    try {
                        if (attempt>=3) {
                            int size=attempt==3 ? 384 : attempt==4 ? 288 : 224;
                            Bitmap small=Bitmap.createScaledBitmap(bitmap,size,size,true);
                            Bitmap soft=Bitmap.createScaledBitmap(small,512,512,true); small.recycle();
                            bitmap.recycle(); bitmap=soft;
                        }
                        animation.add(encode(bitmap,quality[attempt]),end-start);
                    } finally { bitmap.recycle(); }
                    if (animation.size()>500*1024) { tooLarge=true; break; }
                }
                if (!tooLarge) {
                    Result r=new Result(); r.bytes=animation.finish(); r.animated=true; r.duration=duration;
                    r.note="Hareketli: "+fps[attempt]+" kare/sn";
                    if (frames.duration()>10000) r.note+="; ilk 10 saniye alındı";
                    if (attempt>=3) r.note+="; boyut için ayrıntı azaltıldı";
                    if (!Webp.stickerCompatible(r.bytes,Webp.inspect(r.bytes))) throw new IOException("Çıkartma doğrulanamadı");
                    return r;
                }
            }
            throw new IOException("Animasyon 500 KB sınırına indirilemedi; daha kısa dosya deneyin");
        }
    }
    @SuppressWarnings("deprecation")
    private static final class GifFrames implements Frames {
        private final Movie movie;
        GifFrames(Movie m) { movie=m; }
        public int duration() { return movie.duration(); }
        public void reset() { movie.setTime(0); }
        public Bitmap at(int ms) {
            Bitmap out=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(out);
            float scale=Math.min(512f/movie.width(),512f/movie.height());
            c.translate((512-movie.width()*scale)/2,(512-movie.height()*scale)/2); c.scale(scale,scale);
            movie.setTime(ms); movie.draw(c,0,0); return out;
        }
        public void close() {}
    }
    private static final class VideoFrames implements Frames {
        private final MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        private final int length;
        VideoFrames(File f) throws Exception {
            try {
                retriever.setDataSource(f.getAbsolutePath());
                String d=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                length=(int)Math.min(Integer.MAX_VALUE,Long.parseLong(d==null?"0":d));
                if (length<=0) throw new IOException("Video süresi okunamadı");
            } catch (Exception e) { retriever.release(); throw e; }
        }
        public int duration() { return length; }
        public void reset() {}
        public Bitmap at(int ms) throws Exception {
            Bitmap frame=retriever.getScaledFrameAtTime(ms*1000L,MediaMetadataRetriever.OPTION_CLOSEST,512,512);
            if (frame==null) throw new IOException("Video karesi okunamadı / codec desteklenmiyor");
            Bitmap out=square(frame); frame.recycle(); return out;
        }
        public void close() throws Exception { retriever.release(); }
    }
    private static final class WebpFrames implements Frames {
        private final Webp.Info info;
        private final Bitmap canvas;
        private final Canvas draw;
        private int index, time;
        private Webp.Frame previous;
        WebpFrames(Webp.Info i) throws IOException {
            info=i;
            if ((long)i.width*i.height>16_000_000) throw new IOException("WebP çözünürlüğü çok büyük");
            canvas=Bitmap.createBitmap(i.width,i.height,Bitmap.Config.ARGB_8888); draw=new Canvas(canvas); reset();
        }
        public int duration() {
            long length=0;
            for (Webp.Frame f:info.frames) length+=Math.max(8,f.duration);
            return (int)Math.min(Integer.MAX_VALUE,length);
        }
        public void reset() { canvas.eraseColor(info.background); index=0; time=0; previous=null; }
        public Bitmap at(int ms) throws Exception {
            while (index<info.frames.size() && (previous==null || time<=ms)) {
                if (previous!=null && (previous.flags&1)!=0) {
                    Paint p=new Paint(); p.setColor(info.background); p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
                    draw.drawRect(previous.x,previous.y,previous.x+previous.width,previous.y+previous.height,p);
                }
                Webp.Frame f=info.frames.get(index++);
                byte[] encoded=Webp.standalone(f);
                Bitmap decoded=BitmapFactory.decodeByteArray(encoded,0,encoded.length);
                if (decoded==null || decoded.getWidth()!=f.width || decoded.getHeight()!=f.height) {
                    if (decoded!=null) decoded.recycle(); throw new IOException("WebP animasyon karesi okunamadı");
                }
                Paint p=new Paint(FILTER);
                if ((f.flags&2)!=0) p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
                draw.drawBitmap(decoded,f.x,f.y,p); decoded.recycle();
                previous=f; time+=Math.max(8,f.duration);
            }
            return square(canvas);
        }
        public void close() { canvas.recycle(); }
    }
}
