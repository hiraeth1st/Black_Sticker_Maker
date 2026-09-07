package com.aziz.stickeratolyesi;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public final class Importer {
    public interface Status { void set(String text); }
    private final Context context;
    private final Store store;
    private final String job;
    private final Converter.Check check;
    private final Status status;
    public Importer(Context c,String j,Converter.Check cancel,Status s) {
        context=c; store=Store.get(c); job=j; check=cancel; status=s;
    }
    public static String name(Context c,Uri uri) {
        try(Cursor cursor=c.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(cursor!=null && cursor.moveToFirst()) return cursor.getString(0);
        } catch(Exception ignored) {}
        String name=uri.getLastPathSegment(); return name==null?"dosya":name;
    }
    public static boolean supported(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp|gif|heic|heif|bmp|mp4|m4v|mov|webm|mkv|3gp)$");
    }
    public void scan(JSONArray roots) throws Exception {
        int count=0;
        for(int n=0;n<roots.length();n++) {
            check.check(); JSONObject root=roots.getJSONObject(n); Uri uri=Uri.parse(root.getString("uri"));
            if(root.optBoolean("tree")) scanTree(uri);
            else add(uri,name(context,uri));
            status.set("Dosyalar taranıyor · "+(++count)+" / "+roots.length()+" seçim");
        }
        store.scanned(job);
    }
    private void scanTree(Uri tree) throws Exception {
        ArrayDeque<String> pending=new ArrayDeque<>(); Set<String> seen=new HashSet<>();
        pending.add(DocumentsContract.getTreeDocumentId(tree));
        int examined=0;
        while(!pending.isEmpty()) {
            check.check(); String parent=pending.remove(); if(!seen.add(parent)) continue;
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent);
            List<String[]> entries=new ArrayList<>();
            try(Cursor c=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)) {
                if(c==null) throw new IOException("Klasör okunamadı; yeniden seçmeniz gerekebilir");
                while(c.moveToNext()) {
                    check.check();
                    if(++examined>40000) throw new IOException("Tek işlemde en fazla 40.000 klasör/dosya taranabilir");
                    entries.add(new String[]{c.getString(0),c.getString(1),c.getString(2)});
                }
            }
            entries.sort((a,b)->a[1].compareToIgnoreCase(b[1]));
            for(String[] e:entries) {
                check.check();
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(e[2])) pending.add(e[0]);
                else add(DocumentsContract.buildDocumentUriUsingTree(tree,e[0]),e[1]);
            }
            status.set("Klasör taranıyor · "+examined+" öğe");
        }
    }
    private void add(Uri uri,String name) throws Exception {
        if(name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            File archive=new File(store.work(job),"archive_"+hash(uri.toString().getBytes(StandardCharsets.UTF_8))+".zip");
            if(!archive.isFile()) {
                status.set("ZIP hazırlanıyor · "+name);
                File partial=new File(archive.getPath()+".partial");
                try(InputStream in=context.getContentResolver().openInputStream(uri)) { copy(in,partial,8L*1024*1024*1024); }
                if(!partial.renameTo(archive)) throw new IOException("ZIP kaydedilemedi");
            }
            try(ZipFile zip=new ZipFile(archive)) {
                List<String> names=new ArrayList<>(); int total=0; long expanded=0;
                Enumeration<? extends ZipEntry> iterator=zip.entries();
                while(iterator.hasMoreElements()) {
                    check.check(); ZipEntry e=iterator.nextElement();
                    if(++total>40000) throw new IOException("ZIP içinde çok fazla öğe var");
                    if(!e.isDirectory() && supported(e.getName()) && !e.getName().startsWith("__MACOSX/")) {
                        expanded+=Math.max(0,e.getSize());
                        if(expanded>16L*1024*1024*1024) throw new IOException("ZIP açılmış boyutu 16 GB sınırını aşıyor");
                        names.add(e.getName());
                    }
                }
                names.sort(String.CASE_INSENSITIVE_ORDER);
                for(String entry:names) store.task(job,Uri.fromFile(archive).toString(),entry,entry);
            }
        } else if(supported(name)) store.task(job,uri.toString(),"",name);
    }
    public File source(JSONObject task) throws Exception {
        File file=new File(store.work(job),"current.input");
        String entry=task.getString("entry"); Uri uri=Uri.parse(task.getString("uri"));
        if(!entry.isEmpty()) {
            // Entries are streamed into a fixed private filename. Archive paths never become disk paths.
            try(ZipFile zip=new ZipFile(new File(uri.getPath()))) {
                ZipEntry e=zip.getEntry(entry);
                if(e==null) throw new IOException("ZIP öğesi bulunamadı");
                try(InputStream in=zip.getInputStream(e)) { copy(in,file,256L*1024*1024); }
            }
        } else try(InputStream in=context.getContentResolver().openInputStream(uri)) { copy(in,file,256L*1024*1024); }
        return file;
    }
    private void copy(InputStream in,File file,long limit) throws Exception {
        if(in==null) throw new IOException("Dosya açılamadı");
        byte[] buffer=new byte[64*1024]; long size=0;
        try(OutputStream out=new FileOutputStream(file)) {
            int length;
            while((length=in.read(buffer))!=-1) {
                check.check(); size+=length;
                if(size>limit) throw new IOException("Dosya izin verilen boyutu aşıyor");
                if(file.getParentFile().getUsableSpace()<64L*1024*1024) throw new IOException("Telefonda yeterli boş alan yok");
                out.write(buffer,0,length);
            }
        }
        if(size==0) throw new IOException("Dosya boş");
    }
    public String hash(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] bytes=new byte[64*1024];
        try(InputStream in=new FileInputStream(file)) { int n; while((n=in.read(bytes))!=-1) { check.check(); digest.update(bytes,0,n); } }
        return hex(digest.digest());
    }
    private static String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hex(byte[] bytes) {
        StringBuilder b=new StringBuilder(); for(byte v:bytes) b.append(String.format(Locale.ROOT,"%02x",v&255)); return b.toString();
    }
    public static void clear(File file) {
        if(file.isDirectory()) { File[] children=file.listFiles(); if(children!=null) for(File c:children) clear(c); }
        file.delete();
    }
}
