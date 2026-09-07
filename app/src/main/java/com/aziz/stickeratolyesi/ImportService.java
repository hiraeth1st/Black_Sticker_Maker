package com.aziz.stickeratolyesi;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.*;
import org.json.*;
import java.io.File;
import java.util.concurrent.CancellationException;

public final class ImportService extends Service {
    public static volatile boolean running;
    public static volatile String activeJob="";
    private volatile boolean pause;
    private Thread worker;
    private Store store;
    private PowerManager.WakeLock wake;
    private long lastUpdate;
    @Override public void onCreate() {
        super.onCreate(); store=Store.get(this);
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("import","Çıkartma dönüştürme",NotificationManager.IMPORTANCE_LOW));
    }
    public static void start(Context c,String job) {
        c.startForegroundService(new Intent(c,ImportService.class).putExtra("job",job));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null) { stopSelf(); return START_NOT_STICKY; }
        if("pause".equals(intent.getAction())) { pause=true; return START_NOT_STICKY; }
        if(running) return START_NOT_STICKY;
        String job=intent.getStringExtra("job");
        JSONObject data=job==null?null:store.job(job);
        if(data==null || "done".equals(data.optString("state"))) { stopSelf(); return START_NOT_STICKY; }
        activeJob=job; running=true; pause=false;
        Notification n=notification("İşlem hazırlanıyor");
        if(Build.VERSION.SDK_INT>=29) startForeground(10,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(10,n);
        PowerManager pm=getSystemService(PowerManager.class);
        wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"StickerAtolyesi:Import");
        wake.acquire(30*60*1000L);
        worker=new Thread(()->runImport(job),"StickerImport"); worker.start();
        return START_NOT_STICKY;
    }
    private void check() {
        if(pause || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
    private void runImport(String job) {
        try {
            store.jobState(job,"running","Dosyalar hazırlanıyor");
            Importer importer=new Importer(this,job,this::check,this::update);
            JSONObject data=store.job(job);
            if(data.optInt("scanned")==0) importer.scan(new JSONArray(data.getString("roots")));
            JSONObject task;
            while((task=store.nextTask(job))!=null) {
                check();
                if(wake!=null && !wake.isHeld()) wake.acquire(30*60*1000L);
                long id=task.getLong("id");
                update(task.getString("name"));
                File source=null;
                try {
                    source=importer.source(task); String hash=importer.hash(source);
                    if(store.hasMedia(hash)) store.taskState(id,3,hash,"");
                    else {
                        Converter.Result result=Converter.convert(source,task.getString("name"),this::check);
                        check(); store.complete(id,hash,task.getString("name"),result);
                    }
                } catch(CancellationException e) { throw e; }
                catch(Exception e) { store.taskState(id,2,null,error(e)); }
                catch(OutOfMemoryError e) { store.taskState(id,2,null,"Dosya telefonun belleğine sığmadı"); }
                finally { if(source!=null) source.delete(); }
                int[] counts=store.counts(job);
                update((counts[1]+counts[2]+counts[3])+" / "+(counts[0]+counts[1]+counts[2]+counts[3])+" dosya işlendi");
            }
            check(); update("Paketler oluşturuluyor"); store.finish(job);
            Importer.clear(store.work(job));
        } catch(CancellationException e) { store.jobState(job,"paused","Durduruldu. Tamamlanan dosyalar kaydedildi."); }
        catch(Exception e) { store.jobState(job,"error",error(e)); }
        finally {
            running=false;
            if(wake!=null && wake.isHeld()) wake.release();
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }
    private static String error(Throwable e) {
        String m=e.getMessage(); return m==null?e.getClass().getSimpleName():m;
    }
    private void update(String message) {
        store.jobState(activeJob,"running",message);
        long now=SystemClock.elapsedRealtime();
        if(now-lastUpdate>700) {
            if(Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
                getSystemService(NotificationManager.class).notify(10,notification(message));
            lastUpdate=now;
        }
    }
    private Notification notification(String message) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ImportService.class).setAction("pause"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"import").setSmallIcon(R.drawable.ic_app).setContentTitle("Sticker Atölyesi")
            .setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null,"Duraklat",stop).build()).build();
    }
    @Override public void onTimeout(int startId,int fgsType) {
        pause=true;
        if(!activeJob.isEmpty()) store.jobState(activeJob,"paused","Android süre sınırına ulaşıldı. Uygulamadan devam edebilirsiniz.");
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() { pause=true; if(worker!=null) worker.interrupt(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
