package com.aziz.stickeratolyesi;

import android.content.Context;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

public final class ConversionTest extends InstrumentationTestCase {
    private File input(String name) throws Exception {
        File file=new File(getInstrumentation().getTargetContext().getCacheDir(),"test-"+name);
        try(InputStream in=getInstrumentation().getContext().getAssets().open(name); OutputStream out=new FileOutputStream(file)) {
            byte[] b=new byte[4096]; int n; while((n=in.read(b))!=-1) out.write(b,0,n);
        }
        return file;
    }
    private Converter.Result convert(String name) throws Exception { return Converter.convert(input(name),name,()->{}); }
    public void testStaticDimensionsAndPadding() throws Exception {
        Converter.Result r=convert("wide.png"); Webp.Info i=Webp.inspect(r.bytes);
        assertTrue(Webp.stickerCompatible(r.bytes,i)); assertFalse(r.animated);
        Bitmap decoded=BitmapFactory.decodeByteArray(r.bytes,0,r.bytes.length);
        assertEquals(0,Color.alpha(decoded.getPixel(0,0))); assertTrue(Color.alpha(decoded.getPixel(256,256))>250); decoded.recycle();
    }
    public void testExistingWebpIsNotReencoded() throws Exception {
        File f=input("ready.webp"); Converter.Result r=Converter.convert(f,"ready.webp",()->{});
        assertTrue(Arrays.equals(Files.readAllBytes(f.toPath()),r.bytes));
    }
    public void testGifReallyMoves() throws Exception {
        Converter.Result r=convert("moving.gif"); Webp.Info i=Webp.inspect(r.bytes);
        assertTrue(r.animated); assertTrue(Webp.stickerCompatible(r.bytes,i)); assertEquals(900,i.duration);
        assertDifferentFrames(i);
    }
    public void testVideoBecomesAnimation() throws Exception {
        Converter.Result r=convert("moving.mp4"); Webp.Info i=Webp.inspect(r.bytes);
        assertTrue(r.animated); assertTrue(Webp.stickerCompatible(r.bytes,i)); assertDifferentFrames(i);
    }
    public void testAnimatedWebpCanBeResized() throws Exception {
        Converter.Result r=convert("small-animated.webp"); Webp.Info i=Webp.inspect(r.bytes);
        assertTrue(r.animated); assertEquals(512,i.width); assertTrue(Webp.stickerCompatible(r.bytes,i)); assertDifferentFrames(i);
    }
    public void testLongAnimationIsTrimmedAndReported() throws Exception {
        Converter.Result r=convert("long.gif");
        assertEquals(10000,r.duration); assertTrue(r.note.contains("ilk 10 saniye"));
    }
    public void testBrokenInputFails() throws Exception {
        File f=new File(getInstrumentation().getTargetContext().getCacheDir(),"broken.webp"); Files.write(f.toPath(),new byte[]{1,2,3});
        boolean failed=false; try { Converter.convert(f,"broken.webp",()->{}); } catch(Exception expected) { failed=true; }
        assertTrue(failed);
    }
    public void testPackAndProviderContract() throws Exception {
        Context context=getInstrumentation().getTargetContext(); Store store=Store.get(context);
        String job=store.createJob("Test",new JSONArray()); Converter.Result r=convert("wide.png");
        for(int n=0;n<3;n++) {
            store.task(job,"test:"+n,"","sample"+n+".png");
            JSONObject task=store.nextTask(job);
            String hash=String.format(Locale.ROOT,"%064x",1000+n);
            store.complete(task.getLong("id"),hash,"sample.png",r);
        }
        store.finish(job); String id=job+"_0_1";
        try(Cursor cursor=context.getContentResolver().query(Uri.parse("content://"+Store.AUTHORITY+"/metadata/"+id),null,null,null,null)) {
            assertNotNull(cursor); assertTrue(cursor.moveToFirst());
            assertEquals(id,cursor.getString(cursor.getColumnIndexOrThrow("sticker_pack_identifier")));
            assertEquals(0,cursor.getInt(cursor.getColumnIndexOrThrow("animated_sticker_pack")));
        }
        try(Cursor cursor=context.getContentResolver().query(Uri.parse("content://"+Store.AUTHORITY+"/stickers/"+id),null,null,null,null)) {
            assertNotNull(cursor); assertEquals(3,cursor.getCount());
        }
        Uri tray=Uri.parse("content://"+Store.AUTHORITY+"/stickers_asset/"+id+"/tray.png");
        try(InputStream in=context.getContentResolver().openInputStream(tray)) {
            Bitmap b=BitmapFactory.decodeStream(in); assertNotNull(b); assertEquals(96,b.getWidth()); assertEquals(96,b.getHeight()); b.recycle();
        }
        boolean denied=false;
        try { context.getContentResolver().openFileDescriptor(tray,"w"); } catch(Exception expected) { denied=true; }
        assertTrue(denied);
    }
    public void testSingleStaticPackSurvivesLaterImport() throws Exception {
        verifySingle("wide.png",false);
    }
    public void testSingleAnimatedPackAndCompatibleCopies() throws Exception {
        verifySingle("moving.gif",true);
    }
    private void verifySingle(String source,boolean animated) throws Exception {
        Context context=getInstrumentation().getTargetContext(); Store store=Store.get(context);
        String hash=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        String job=store.createJob("Single",new JSONArray());
        store.task(job,"test:"+hash,"",source);
        store.complete(store.nextTask(job).getLong("id"),hash,source,convert(source));
        store.finish(job);
        String id=job+"_"+(animated?1:0)+"_1";
        assertEquals(1,store.items(id).size());
        JSONObject separate=store.createSinglePack(hash,"Separate",false);
        String later=store.createJob("Later",new JSONArray()); store.finish(later);
        assertNotNull(store.pack(id)); assertEquals(1,store.items(id).size());
        assertEquals(1,store.items(separate.getString("id")).size());
        store.finish(job); assertEquals(1,store.items(id).size());
        try(Cursor c=context.getContentResolver().query(Uri.parse("content://"+Store.AUTHORITY+"/metadata/"+id),null,null,null,null)) {
            assertNotNull(c); assertEquals(1,c.getCount()); assertTrue(c.moveToFirst());
            assertEquals(animated?1:0,c.getInt(c.getColumnIndexOrThrow("animated_sticker_pack")));
        }
        JSONObject compatible=store.createSinglePack(hash,"Compatible",true);
        assertEquals(3,store.items(compatible.getString("id")).size());
        Set<String> names=new HashSet<>();
        try(Cursor c=context.getContentResolver().query(Uri.parse("content://"+Store.AUTHORITY+"/stickers/"+compatible.getString("id")),null,null,null,null)) {
            assertNotNull(c); assertEquals(3,c.getCount());
            while(c.moveToNext()) {
                String name=c.getString(c.getColumnIndexOrThrow("sticker_file_name")); assertTrue(names.add(name));
                Uri uri=Uri.parse("content://"+Store.AUTHORITY+"/stickers_asset/"+compatible.getString("id")+"/"+name);
                try(InputStream in=context.getContentResolver().openInputStream(uri)) {
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int n;
                    while((n=in.read(buffer))!=-1) bytes.write(buffer,0,n);
                    assertTrue(Arrays.equals(Files.readAllBytes(store.mediaFile(hash).toPath()),bytes.toByteArray()));
                }
            }
        }
        Uri invalid=Uri.parse("content://"+Store.AUTHORITY+"/stickers_asset/"+id+"/"+hash+"_29.webp");
        boolean denied=false; try { context.getContentResolver().openFileDescriptor(invalid,"r"); } catch(FileNotFoundException expected) { denied=true; }
        assertTrue(denied);
    }
    private void assertDifferentFrames(Webp.Info i) throws Exception {
        byte[] a=Webp.standalone(i.frames.get(0)), b=Webp.standalone(i.frames.get(i.frames.size()-1));
        Bitmap first=BitmapFactory.decodeByteArray(a,0,a.length), last=BitmapFactory.decodeByteArray(b,0,b.length);
        assertFalse(first.sameAs(last)); first.recycle(); last.recycle();
    }
}
