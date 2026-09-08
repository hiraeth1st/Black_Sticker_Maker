package com.aziz.stickeratolyesi;

import android.content.*;
import android.content.res.AssetFileDescriptor;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Read-only WhatsApp contract. No caller-supplied filesystem paths are accepted. */
public final class StickerProvider extends ContentProvider {
    private Store store;
    private static final String[] META={"sticker_pack_identifier","sticker_pack_name","sticker_pack_publisher","sticker_pack_icon",
        "android_play_store_link","ios_app_download_link","sticker_pack_publisher_email","sticker_pack_publisher_website",
        "sticker_pack_privacy_policy_website","sticker_pack_license_agreement_website","image_data_version",
        "whatsapp_will_not_cache_stickers","animated_sticker_pack"};
    @Override public boolean onCreate() { store=Store.get(getContext()); return true; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sortOrder) {
        List<String> p=uri.getPathSegments();
        if(p.size()>=1 && p.get(0).equals("metadata") && p.size()<=2) {
            MatrixCursor c=new MatrixCursor(META);
            List<JSONObject> packs;
            if(p.size()==1) packs=store.packs();
            else { JSONObject pack=store.pack(p.get(1)); packs=pack==null?Collections.emptyList():Collections.singletonList(pack); }
            for(JSONObject pack:packs) {
                if(store.items(pack.optString("id")).isEmpty()) continue;
                c.addRow(new Object[]{pack.optString("id"),pack.optString("name"),"Sticker Atölyesi","tray.png",
                    "","","","","","",pack.optString("version"),0,pack.optInt("animated")});
            }
            c.setNotificationUri(getContext().getContentResolver(),uri); return c;
        }
        if(p.size()==2 && p.get(0).equals("stickers")) {
            MatrixCursor c=new MatrixCursor(new String[]{"sticker_file_name","sticker_emoji","sticker_accessibility_text"});
            List<JSONObject> items=store.items(p.get(1));
            Set<String> seen=new HashSet<>();
            for(JSONObject item:items) {
                String hash=item.optString("hash");
                String filename=hash+(seen.add(hash)?"":"_"+item.optInt("position"))+".webp";
                c.addRow(new Object[]{filename,"🙂",item.optString("name")});
            }
            c.setNotificationUri(getContext().getContentResolver(),uri); return c;
        }
        throw new IllegalArgumentException("Desteklenmeyen çıkartma sorgusu");
    }
    private File asset(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode)) throw new FileNotFoundException("Salt okunur");
        List<String> p=uri.getPathSegments();
        if(p.size()!=3 || !p.get(0).equals("stickers_asset") || store.pack(p.get(1))==null) throw new FileNotFoundException("Paket bulunamadı");
        String name=p.get(2); File file;
        if("tray.png".equals(name)) file=store.trayFile(p.get(1));
        else {
            if(!name.matches("[a-f0-9]{64}(_[0-9]+)?\\.webp")) throw new FileNotFoundException("Geçersiz çıkartma");
            String hash=name.substring(0,64);
            if(!store.member(p.get(1),hash)) throw new FileNotFoundException("Paketin dışında");
            if(name.charAt(64)=='_') {
                String position=name.substring(65,name.length()-5);
                boolean found=false;
                for(JSONObject item:store.items(p.get(1)))
                    if(position.equals(Integer.toString(item.optInt("position"))) && hash.equals(item.optString("hash"))) found=true;
                if(!found) throw new FileNotFoundException("Geçersiz çıkartma sırası");
            }
            file=store.mediaFile(hash);
        }
        if(!file.isFile()) throw new FileNotFoundException("Dosya bulunamadı"); return file;
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri,String mode) throws FileNotFoundException {
        File f=asset(uri,mode); return new AssetFileDescriptor(ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY),0,f.length());
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(asset(uri,mode),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) {
        List<String> p=uri.getPathSegments();
        if(p.size()==3 && p.get(0).equals("stickers_asset")) return "tray.png".equals(p.get(2))?"image/png":"image/webp";
        if(p.size()==2 && p.get(0).equals("metadata")) return "vnd.android.cursor.item/vnd."+Store.AUTHORITY+".metadata";
        if(p.size()==1 && p.get(0).equals("metadata")) return "vnd.android.cursor.dir/vnd."+Store.AUTHORITY+".metadata";
        if(p.size()==2 && p.get(0).equals("stickers")) return "vnd.android.cursor.dir/vnd."+Store.AUTHORITY+".stickers";
        throw new IllegalArgumentException("Geçersiz URI");
    }
    @Override public Uri insert(Uri uri,ContentValues v) { throw new UnsupportedOperationException("Salt okunur"); }
    @Override public int update(Uri uri,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException("Salt okunur"); }
    @Override public int delete(Uri uri,String s,String[] a) { throw new UnsupportedOperationException("Salt okunur"); }
}
