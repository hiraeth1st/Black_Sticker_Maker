package com.aziz.stickeratolyesi;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

public final class Store extends SQLiteOpenHelper {
    public static final String AUTHORITY="com.aziz.stickeratolyesi.stickers";
    private static Store instance;
    private final Context context;
    public static synchronized Store get(Context c) {
        if (instance==null) instance=new Store(c.getApplicationContext());
        return instance;
    }
    private Store(Context c) { super(c,"stickers.db",null,1); context=c; setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, name TEXT NOT NULL, roots TEXT NOT NULL, state TEXT NOT NULL, scanned INTEGER NOT NULL DEFAULT 0, message TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, job TEXT NOT NULL REFERENCES jobs(id), uri TEXT NOT NULL, entry TEXT NOT NULL DEFAULT '', name TEXT NOT NULL, state INTEGER NOT NULL DEFAULT 0, hash TEXT, error TEXT NOT NULL DEFAULT '', UNIQUE(job,uri,entry))");
        db.execSQL("CREATE INDEX tasks_job_state ON tasks(job,state,id)");
        db.execSQL("CREATE TABLE media (hash TEXT PRIMARY KEY, name TEXT NOT NULL, animated INTEGER NOT NULL, duration INTEGER NOT NULL, note TEXT NOT NULL)");
        db.execSQL("CREATE TABLE packs (id TEXT PRIMARY KEY, name TEXT NOT NULL, animated INTEGER NOT NULL, version TEXT NOT NULL, created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE pack_items (pack TEXT NOT NULL REFERENCES packs(id) ON DELETE CASCADE, position INTEGER NOT NULL, hash TEXT NOT NULL REFERENCES media(hash), PRIMARY KEY(pack,position))");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV) { throw new IllegalStateException("Desteklenmeyen veritabanı sürümü"); }
    public File root() { File f=new File(context.getFilesDir(),"stickers"); if (!f.exists()) f.mkdirs(); return f; }
    public File mediaFile(String hash) {
        if (hash==null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("hash");
        return new File(root(),hash+".webp");
    }
    public File trayFile(String id) {
        if (!id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("pack id");
        return new File(root(),id+".png");
    }
    public File work(String id) {
        if (!id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("job id");
        File f=new File(context.getFilesDir(),"jobs/"+id); if (!f.exists()) f.mkdirs(); return f;
    }
    public String createJob(String name,JSONArray roots) {
        String id=UUID.randomUUID().toString().replace("-","");
        ContentValues v=new ContentValues(); v.put("id",id); v.put("name",name); v.put("roots",roots.toString());
        v.put("state","paused"); v.put("created",System.currentTimeMillis()); getWritableDatabase().insertOrThrow("jobs",null,v); return id;
    }
    public void jobState(String id,String state,String message) {
        ContentValues v=new ContentValues(); v.put("state",state); v.put("message",message);
        getWritableDatabase().update("jobs",v,"id=?",new String[]{id});
    }
    public void scanned(String id) {
        ContentValues v=new ContentValues(); v.put("scanned",1); getWritableDatabase().update("jobs",v,"id=?",new String[]{id});
    }
    public JSONObject job(String id) { return one("SELECT * FROM jobs WHERE id=?",new String[]{id}); }
    public JSONObject latestJob() { return one("SELECT * FROM jobs ORDER BY created DESC LIMIT 1",null); }
    public void task(String job,String uri,String entry,String name) {
        ContentValues v=new ContentValues(); v.put("job",job); v.put("uri",uri); v.put("entry",entry); v.put("name",name);
        getWritableDatabase().insertWithOnConflict("tasks",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    public JSONObject nextTask(String job) { return one("SELECT * FROM tasks WHERE job=? AND state=0 ORDER BY id LIMIT 1",new String[]{job}); }
    public void taskState(long id,int state,String hash,String error) {
        ContentValues v=new ContentValues(); v.put("state",state); v.put("hash",hash); v.put("error",error);
        getWritableDatabase().update("tasks",v,"id=?",new String[]{Long.toString(id)});
    }
    public boolean hasMedia(String hash) {
        return one("SELECT hash FROM media WHERE hash=?",new String[]{hash})!=null && mediaFile(hash).isFile();
    }
    public void complete(long task,String hash,String name,Converter.Result r) throws Exception {
        File target=mediaFile(hash), temp=new File(root(),hash+".tmp");
        Files.write(temp.toPath(),r.bytes);
        if (!temp.renameTo(target)) throw new IOException("Çıkartma kaydedilemedi");
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues v=new ContentValues(); v.put("hash",hash); v.put("name",name);
            v.put("animated",r.animated?1:0); v.put("duration",r.duration); v.put("note",r.note);
            db.insertWithOnConflict("media",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            taskState(task,1,hash,""); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public int[] counts(String job) {
        int[] result=new int[4];
        try(Cursor c=getReadableDatabase().rawQuery("SELECT state,COUNT(*) FROM tasks WHERE job=? GROUP BY state",new String[]{job})) {
            while(c.moveToNext()) result[c.getInt(0)]=c.getInt(1);
        }
        return result;
    }
    public synchronized void finish(String job) throws Exception {
        JSONObject j=job(job);
        if(j==null || "done".equals(j.optString("state"))) return;
        // Files are generated first; publishing metadata and marking the job done are one transaction.
        List<JSONObject> newPacks=new ArrayList<>();
        List<String> oldDrafts=new ArrayList<>();
        for(int animated=0;animated<2;animated++) {
            List<JSONObject> items=new ArrayList<>();
            for(JSONObject draft:rows("SELECT p.id FROM packs p JOIN pack_items i ON i.pack=p.id WHERE p.animated=? GROUP BY p.id HAVING COUNT(i.hash)<3 ORDER BY p.created",new String[]{""+animated})) {
                String id=draft.getString("id"); oldDrafts.add(id); items.addAll(items(id));
            }
            items.addAll(rows("SELECT m.* FROM tasks t JOIN media m ON m.hash=t.hash WHERE t.job=? AND t.state=1 AND m.animated=? ORDER BY t.id",new String[]{job,""+animated}));
            int offset=0,number=1;
            for(int size:PackPlanner.sizes(items.size())) {
                String id=job+"_"+animated+"_"+number;
                Converter.tray(mediaFile(items.get(offset).getString("hash")),trayFile(id));
                JSONObject p=new JSONObject(); p.put("id",id); p.put("animated",animated);
                p.put("name",j.getString("name")+" · "+(animated==1?"Hareketli":"Sabit")+" "+number);
                JSONArray hashes=new JSONArray(); for(int n=0;n<size;n++) hashes.put(items.get(offset++).getString("hash"));
                p.put("hashes",hashes); newPacks.add(p); number++;
            }
        }
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            for(String draft:oldDrafts) db.delete("packs","id=?",new String[]{draft});
            for(JSONObject p:newPacks) {
                ContentValues v=new ContentValues(); v.put("id",p.getString("id")); v.put("name",p.getString("name"));
                v.put("animated",p.getInt("animated")); v.put("version","1"); v.put("created",System.currentTimeMillis());
                db.insertOrThrow("packs",null,v);
                JSONArray hashes=p.getJSONArray("hashes");
                for(int n=0;n<hashes.length();n++) {
                    ContentValues item=new ContentValues(); item.put("pack",p.getString("id")); item.put("position",n); item.put("hash",hashes.getString(n));
                    db.insertOrThrow("pack_items",null,item);
                }
            }
            jobState(job,"done",newPacks.size()+" paket hazır"); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        for(String draft:oldDrafts) trayFile(draft).delete();
        context.getContentResolver().notifyChange(android.net.Uri.parse("content://"+AUTHORITY+"/metadata"),null);
    }
    public List<JSONObject> packs() {
        return rows("SELECT p.*,COUNT(i.hash) AS count FROM packs p JOIN pack_items i ON i.pack=p.id GROUP BY p.id ORDER BY p.created,p.id",null);
    }
    public JSONObject pack(String id) { return one("SELECT * FROM packs WHERE id=?",new String[]{id}); }
    public List<JSONObject> items(String id) {
        return rows("SELECT m.*,i.position FROM pack_items i JOIN media m ON m.hash=i.hash WHERE i.pack=? ORDER BY i.position",new String[]{id});
    }
    public boolean member(String pack,String hash) {
        return one("SELECT hash FROM pack_items WHERE pack=? AND hash=?",new String[]{pack,hash})!=null;
    }
    public void renamePack(String id,String name) {
        ContentValues v=new ContentValues(); v.put("name",name); v.put("version",Long.toString(System.currentTimeMillis()));
        getWritableDatabase().update("packs",v,"id=?",new String[]{id});
        context.getContentResolver().notifyChange(android.net.Uri.parse("content://"+AUTHORITY+"/metadata/"+id),null);
    }
    public String report(String job) {
        StringBuilder s=new StringBuilder();
        for(JSONObject t:rows("SELECT name,state,error FROM tasks WHERE job=? AND state IN (2,3) ORDER BY id",new String[]{job}))
            s.append(t.optString("name")).append(": ").append(t.optInt("state")==3?"Aynı dosya daha önce alındı":t.optString("error")).append('\n');
        for(JSONObject t:rows("SELECT m.name,m.note FROM tasks t JOIN media m ON m.hash=t.hash WHERE t.job=? AND t.state=1 AND m.note!='' ORDER BY t.id",new String[]{job}))
            s.append(t.optString("name")).append(": ").append(t.optString("note")).append('\n');
        return s.length()==0?"Atlanan dosya veya dönüşüm notu yok.":s.toString();
    }
    public List<JSONObject> rows(String query,String[] args) {
        List<JSONObject> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(query,args)) {
            while(c.moveToNext()) {
                JSONObject o=new JSONObject();
                for(int n=0;n<c.getColumnCount();n++) try {
                    if(c.getType(n)==Cursor.FIELD_TYPE_INTEGER) o.put(c.getColumnName(n),c.getLong(n));
                    else o.put(c.getColumnName(n),c.isNull(n)?JSONObject.NULL:c.getString(n));
                } catch(JSONException impossible) { throw new IllegalStateException(impossible); }
                result.add(o);
            }
        }
        return result;
    }
    private JSONObject one(String query,String[] args) { List<JSONObject> r=rows(query,args); return r.isEmpty()?null:r.get(0); }
}
