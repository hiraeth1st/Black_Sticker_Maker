package com.aziz.stickeratolyesi;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public final class MainActivity extends Activity {
    private static final int FILES=40,TREE=41,ADD=42,BACKUP=43,SINGLE=44;
    private static final int BG=0xff101715,CARD=0xff1d2923,INK=0xfff4f7ed,MUTED=0xffa8b9ab,LIME=0xffb7f279;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(4*1024*1024) {
        @Override protected int sizeOf(String k,Bitmap v) { return v.getAllocationByteCount(); }
    };
    private Store store;
    private EditText name,search;
    private TextView summary,progressText,statusTitle;
    private ProgressBar progress;
    private LinearLayout controls;
    private Button fileButton,folderButton,singleButton;
    private PackAdapter adapter;
    private boolean preparing,detailOpen;
    private String signature="",lastAdd="";
    private final List<JSONObject> all=new ArrayList<>(),visible=new ArrayList<>();
    private final Runnable tick=new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this,1000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); store=Store.get(this);
        if(state!=null) lastAdd=state.getString("lastAdd","");
        LinearLayout root=column(); root.setPadding(dp(20),dp(12),dp(20),0); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(dp(20),insets.getSystemWindowInsetTop()+dp(12),dp(20),insets.getSystemWindowInsetBottom()); return insets;
        });
        TextView brand=text("STICKER ATÖLYESİ",12,LIME); brand.setLetterSpacing(.15f); root.addView(brand);
        TextView title=text("Dosyaların.\nÇıkartmaların.",31,INK); title.setTypeface(null,Typeface.BOLD); title.setPadding(0,dp(8),0,dp(7)); root.addView(title);
        root.addView(text("Topluca seç, paketle, WhatsApp’a ekle.",14,MUTED));
        LinearLayout box=column(); box.setPadding(dp(16),dp(10),dp(16),dp(12)); box.setBackground(round(CARD,18));
        LinearLayout.LayoutParams boxParams=match(); boxParams.topMargin=dp(18); root.addView(box,boxParams);
        name=new EditText(this); name.setSingleLine(true); name.setTextSize(16); name.setTextColor(INK); name.setHintTextColor(MUTED);
        name.setHint("Paket adı · ör. Kediler"); name.setText(getPreferences(0).getString("name","Çıkartmalarım"));
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(70)}); box.addView(name);
        LinearLayout importRow=row();
        folderButton=button("Klasör seç",true,v->pick(TREE)); fileButton=button("Dosya / ZIP",false,v->pick(FILES));
        importRow.addView(folderButton,weight()); importRow.addView(fileButton,weight()); box.addView(importRow);
        singleButton=button("Tek çıkartma oluştur",false,v->pick(SINGLE)); box.addView(singleButton);
        TextView hint=text("PNG, JPG, WebP, GIF ve kısa videolar\nAlt klasörler dahil · Tamamı telefonda işlenir",11,MUTED); hint.setPadding(0,dp(6),0,0); box.addView(hint);
        statusTitle=text("Başlamak için dosyalarını seç",14,INK); statusTitle.setPadding(0,dp(14),0,dp(3)); root.addView(statusTitle);
        progressText=text("",12,MUTED); root.addView(progressText);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));
        controls=row(); root.addView(controls);
        LinearLayout section=row(); summary=text("Paketlerim",18,INK); summary.setTypeface(null,Typeface.BOLD); section.setGravity(Gravity.CENTER_VERTICAL);
        section.addView(summary,new LinearLayout.LayoutParams(0,-2,1));
        section.addView(button("Yedekle",false,v->backupPicker()),new LinearLayout.LayoutParams(-2,dp(48))); root.addView(section);
        search=new EditText(this); search.setTextSize(14); search.setSingleLine(true); search.setTextColor(INK); search.setHintTextColor(MUTED); search.setHint("Paket ara"); root.addView(search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { filter(); }
            public void afterTextChanged(Editable e) {}
        });
        ListView list=new ListView(this); list.setDivider(null); list.setDividerHeight(dp(8)); list.setClipToPadding(false); list.setPadding(0,dp(8),0,dp(20));
        adapter=new PackAdapter(); list.setAdapter(adapter); list.setOnItemClickListener((p,v,pos,id)->details(visible.get(pos)));
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("lastAdd",lastAdd); }
    @Override protected void onResume() { super.onResume(); signature=""; handler.removeCallbacks(tick); handler.post(tick); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(tick); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy(); }
    private void pick(int request) {
        if(!canStart()) return;
        getPreferences(0).edit().putString("name",packName()).apply();
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},50);
        Intent i;
        if(request==TREE) i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        else i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,request!=SINGLE);
        if(request==SINGLE) i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i,request); } catch(ActivityNotFoundException e) { message("Dosya seçici bulunamadı"); }
    }
    private String packName() { String n=name.getText().toString().trim(); return n.isEmpty()?"Çıkartmalarım":n; }
    private boolean canStart() {
        JSONObject job=store.latestJob();
        if(preparing || ImportService.running || (job!=null && !"done".equals(job.optString("state")))) {
            message("Önce mevcut işlemi tamamla veya işlenen dosyalardan paket oluştur."); return false;
        }
        return true;
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==ADD) {
            String error=data==null?null:data.getStringExtra("validation_error");
            if(error!=null && !error.isEmpty()) {
                List<JSONObject> items=store.items(lastAdd);
                if(items.size()==1) new AlertDialog.Builder(this).setTitle("WhatsApp tekli paketi kabul etmedi")
                    .setMessage(error+"\n\nİstersen aynı çıkartmanın 3 kopyasını içeren ayrı bir uyumlu paket oluşturabilirsin. Tekli paketin korunur.")
                    .setNegativeButton("Kapat",null).setPositiveButton("3 kopyalı paket",(d,w)->singlePack(items.get(0).optString("hash"),items.get(0).optString("name"),true)).show();
                else message("WhatsApp paketi ekleyemedi: "+error);
            }
            else if(result==RESULT_OK) message("Paket WhatsApp’a eklendi.");
            else if(!lastAdd.isEmpty()) message("Ekleme tamamlanmadı. Paketi yeniden deneyebilirsin.");
            return;
        }
        if(result!=RESULT_OK || data==null) return;
        if(request==BACKUP) { if(data.getData()!=null) writeBackup(data.getData()); return; }
        if(request!=FILES && request!=TREE && request!=SINGLE) return;
        JSONArray roots=new JSONArray(); Set<String> seen=new HashSet<>(); List<Uri> uris=new ArrayList<>();
        if(data.getClipData()!=null) for(int n=0;n<data.getClipData().getItemCount();n++) uris.add(data.getClipData().getItemAt(n).getUri());
        else if(data.getData()!=null) uris.add(data.getData());
        if(request==SINGLE && uris.size()!=1) { message("Lütfen yalnızca bir görsel, GIF veya video seç."); return; }
        for(Uri uri:uris) {
            if(!seen.add(uri.toString())) continue;
            try { getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch(SecurityException e) {
                message("Bu konum kalıcı okuma izni vermedi. Dosyaları telefonun Dosyalar uygulamasından seç."); return;
            }
            try { roots.put(new JSONObject().put("uri",uri.toString()).put("tree",request==TREE).put("single",request==SINGLE)); }
            catch(JSONException e) { message("Dosya seçimi okunamadı"); return; }
        }
        if(roots.length()==0) return;
        preparing=true; String label=packName(); refresh();
        io.execute(()->{
            try {
                String id=store.createJob(label,roots);
                runOnUiThread(()->{ preparing=false; startImport(id); });
            } catch(Exception e) { runOnUiThread(()->{ preparing=false; message(e.getMessage()); }); }
        });
    }
    private void startImport(String job) {
        try { ImportService.start(this,job); }
        catch(Exception e) { store.jobState(job,"paused","İşlem başlatılamadı: "+e.getMessage()); message("Uygulama açıkken Devam et düğmesine bas."); }
        signature=""; refresh();
    }
    private void refresh() {
        if(isFinishing() || isDestroyed()) return;
        JSONObject job=store.latestJob();
        int[] c=job==null?new int[4]:store.counts(job.optString("id"));
        String state=job==null?"":job.optString("state");
        String key=(job==null?"":job.toString())+Arrays.toString(c)+ImportService.running+preparing;
        if(key.equals(signature)) return; signature=key;
        boolean busy=preparing||ImportService.running;
        boolean unfinished=job!=null&&!state.equals("done");
        singleButton.setEnabled(!busy&&!unfinished); fileButton.setEnabled(!busy&&!unfinished); folderButton.setEnabled(!busy&&!unfinished);
        int total=c[0]+c[1]+c[2]+c[3],done=total-c[0];
        progress.setMax(Math.max(1,total)); progress.setProgress(done); progress.setIndeterminate(busy&&total==0);
        statusTitle.setText(preparing?"Seçim kaydediliyor":job==null?"Başlamak için dosyalarını seç":state.equals("done")?(c[1]>0?"Paketlerin hazır":"İşlem tamamlandı · yeni çıkartma yok"):busy?"Çıkartmalar hazırlanıyor":"İşlem bekliyor");
        progressText.setText(job==null?"Tekli veya toplu çıkartma oluşturabilirsin.":job.optString("message")+"\n"+c[1]+" hazır · "+c[2]+" hata · "+c[3]+" aynı dosya · "+done+" / "+total);
        controls.removeAllViews();
        if(job!=null) {
            String id=job.optString("id");
            if(ImportService.running) controls.addView(button("Duraklat",false,v->{ startService(new Intent(this,ImportService.class).setAction("pause")); }),weight());
            else if(!busy&&!state.equals("done")) {
                controls.addView(button("Devam et",true,v->startImport(id)),weight());
                controls.addView(button("İşlenenleri paketle",false,v->finishEarly(id)),weight());
            }
            if(!busy) controls.addView(button("Rapor",false,v->report(id)),new LinearLayout.LayoutParams(-2,dp(46)));
        }
        if(!busy) {
            all.clear(); all.addAll(store.packs()); int stickers=0; for(JSONObject p:all) stickers+=p.optInt("count");
            summary.setText("Paketlerim · "+all.size()+"\n"+stickers+" çıkartma"); filter();
        }
    }
    private void finishEarly(String id) {
        if(preparing||ImportService.running) return;
        new AlertDialog.Builder(this).setTitle("İşlenen dosyaları paketle?")
            .setMessage("Hazır çıkartmalar paketlenecek. Henüz işlenmeyen dosyalar bu işlemden çıkarılacak; sonradan yeniden seçebilirsin.")
            .setNegativeButton("Vazgeç",null).setPositiveButton("Paketle",(d,w)->{
                preparing=true; refresh();
                io.execute(()->{
                    try { store.finish(id); Importer.clear(store.work(id)); }
                    catch(Exception e) { runOnUiThread(()->message(e.getMessage())); }
                    finally { runOnUiThread(()->{ preparing=false; signature=""; refresh(); }); }
                });
            }).show();
    }
    private void filter() {
        String q=search==null?"":search.getText().toString().toLowerCase(new Locale("tr"));
        visible.clear(); for(JSONObject p:all) if(p.optString("name").toLowerCase(new Locale("tr")).contains(q)) visible.add(p);
        if(adapter!=null) adapter.notifyDataSetChanged();
    }
    private void details(JSONObject pack) {
        detailOpen=true; String id=pack.optString("id"); List<JSONObject> items=store.items(id);
        LinearLayout content=column(); content.setPadding(dp(16),dp(8),dp(16),dp(16));
        content.addView(text(items.size()+" çıkartma · "+(pack.optInt("animated")==1?"Hareketli":"Sabit")+"\nBüyütmek / oynatmak için dokun.",13,MUTED));
        GridLayout grid=new GridLayout(this); grid.setColumnCount(4);
        int width=Math.max(48,(getResources().getDisplayMetrics().widthPixels-dp(96))/4);
        for(JSONObject item:items) {
            ImageView iv=new ImageView(this); iv.setScaleType(ImageView.ScaleType.FIT_CENTER); iv.setPadding(dp(3),dp(3),dp(3),dp(3));
            iv.setBackground(round(CARD,10));
            GridLayout.LayoutParams lp=new GridLayout.LayoutParams(); lp.width=width; lp.height=width; lp.setMargins(dp(2),dp(2),dp(2),dp(2)); grid.addView(iv,lp);
            File file=store.mediaFile(item.optString("hash")); thumbnail(iv,file);
            iv.setContentDescription(item.optString("name")); iv.setOnClickListener(v->preview(file,item.optString("name")));
        }
        content.addView(grid);
        if(items.size()==1) content.addView(button("3 kopyalı uyumlu paket oluştur",false,v->
            new AlertDialog.Builder(this).setTitle("Uyumlu paket oluştur?")
                .setMessage("WhatsApp'ın en az 3 çıkartma isteyen sürümleri için aynı çıkartmanın 3 kopyası ayrı pakete konur. Tekli paket korunur.")
                .setNegativeButton("Vazgeç",null).setPositiveButton("Oluştur",(d,w)->singlePack(items.get(0).optString("hash"),pack.optString("name"),true)).show()));
        if(items.size()<3) content.addView(text("Tekli aktarım WhatsApp sürümüne bağlıdır. Reddedilirse tek çıkartma için 3 kopyalı uyumlu paket oluşturabilirsin.",13,0xffffc88c));
        ScrollView scroll=new ScrollView(this); scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(pack.optString("name")).setView(scroll)
            .setPositiveButton("WhatsApp’a ekle",(d,w)->addPack(pack)).setNeutralButton("Ad değiştir",(d,w)->rename(pack)).setNegativeButton("Kapat",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!items.isEmpty()));
        dialog.setOnDismissListener(d->detailOpen=false); dialog.show();
    }
    private void preview(File file,String label) {
        ImageView image=new ImageView(this); image.setAdjustViewBounds(true); image.setMinimumHeight(dp(250)); image.setPadding(dp(16),dp(16),dp(16),dp(16));
        AlertDialog d=new AlertDialog.Builder(this).setTitle(label).setView(image).setPositiveButton("Kapat",null)
            .setNeutralButton("Ayrı paket yap",(dialog,which)->singlePack(file.getName().substring(0,64),label,false)).create();
        final Drawable[] shown=new Drawable[1];
        d.setOnDismissListener(x->{ if(shown[0] instanceof Animatable) ((Animatable)shown[0]).stop(); }); d.show();
        io.execute(()->{
            try {
                Drawable drawable=ImageDecoder.decodeDrawable(ImageDecoder.createSource(file));
                runOnUiThread(()->{
                    if(!d.isShowing()) return; shown[0]=drawable; image.setImageDrawable(drawable);
                    if(drawable instanceof Animatable) ((Animatable)drawable).start();
                });
            } catch(Exception e) { runOnUiThread(()->message("Önizleme açılamadı")); }
        });
    }
    private void rename(JSONObject pack) {
        EditText edit=new EditText(this); edit.setSingleLine(true); edit.setText(pack.optString("name")); edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        new AlertDialog.Builder(this).setTitle("Paket adı").setView(edit).setNegativeButton("Vazgeç",null).setPositiveButton("Kaydet",(d,w)->{
            String value=edit.getText().toString().trim(); if(!value.isEmpty()) { store.renamePack(pack.optString("id"),value); signature=""; refresh(); }
        }).show();
    }
    private void addPack(JSONObject pack) {
        if(store.items(pack.optString("id")).isEmpty()) { message("Paket boş."); return; }
        List<String> targets=new ArrayList<>(),labels=new ArrayList<>();
        for(String target:new String[]{"com.whatsapp","com.whatsapp.w4b"}) {
            try { getPackageManager().getPackageInfo(target,0); targets.add(target); labels.add(target.endsWith("w4b")?"WhatsApp Business":"WhatsApp"); }
            catch(PackageManager.NameNotFoundException ignored) {}
        }
        if(targets.isEmpty()) { message("WhatsApp veya WhatsApp Business yüklü değil."); return; }
        if(targets.size()==1) launchPack(pack,targets.get(0));
        else new AlertDialog.Builder(this).setTitle("Nereye eklensin?").setItems(labels.toArray(new String[0]),(d,n)->launchPack(pack,targets.get(n))).show();
    }
    private void singlePack(String hash,String label,boolean compatible) {
        io.execute(()->{
            try {
                JSONObject pack=store.createSinglePack(hash,label+(compatible?" · 3 kopya":" · Tekli"),compatible);
                runOnUiThread(()->{ if(isFinishing()||isDestroyed()) return; signature=""; refresh(); addPack(pack); });
            } catch(Exception e) { runOnUiThread(()->message(e.getMessage())); }
        });
    }
    private void launchPack(JSONObject pack,String target) {
        lastAdd=pack.optString("id");
        Intent intent=new Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").setPackage(target)
            .putExtra("sticker_pack_id",lastAdd).putExtra("sticker_pack_authority",Store.AUTHORITY).putExtra("sticker_pack_name",pack.optString("name"));
        try { startActivityForResult(intent,ADD); }
        catch(ActivityNotFoundException e) { message("Bu WhatsApp sürümünde paket ekleme ekranı bulunamadı."); }
    }
    private void report(String id) {
        io.execute(()->{
            String report=store.report(id);
            runOnUiThread(()->{
                TextView body=text(report.length()>40000?report.substring(0,40000)+"\n… Tamamı için Kopyala.":report,13,INK); body.setTextIsSelectable(true); body.setPadding(dp(16),dp(8),dp(16),dp(8));
                ScrollView scroll=new ScrollView(this); scroll.addView(body);
                new AlertDialog.Builder(this).setTitle("Dönüşüm raporu").setView(scroll).setNegativeButton("Kapat",null).setPositiveButton("Kopyala",(d,w)->{
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Dönüşüm raporu",report));
                }).show();
            });
        });
    }
    private void backupPicker() {
        if(store.packs().isEmpty()) { message("Önce bir paket oluştur."); return; }
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Sticker-Atolyesi-Yedek.zip");
        try { startActivityForResult(intent,BACKUP); } catch(ActivityNotFoundException e) { message("Dosya kaydetme ekranı bulunamadı"); }
    }
    private void writeBackup(Uri uri) {
        message("Yedek hazırlanıyor. İşlem bitene kadar uygulamayı açık tut.");
        io.execute(()->{
            try(OutputStream destination=getContentResolver().openOutputStream(uri,"w")) {
                if(destination==null) throw new IOException("Yedek dosyası açılamadı");
                try(ZipOutputStream zip=new ZipOutputStream(destination)) {
                    JSONArray manifest=new JSONArray(); byte[] buffer=new byte[64*1024];
                    for(JSONObject p:store.packs()) {
                        String id=p.optString("id"); String folder=p.optString("name").replaceAll("[^\\p{L}\\p{N} _-]","_")+"_"+id;
                        JSONArray files=new JSONArray();
                        for(JSONObject item:store.items(id)) {
                            String entry=folder+"/"+item.optInt("position")+"_"+item.optString("hash")+".webp"; zip.putNextEntry(new ZipEntry(entry));
                            try(InputStream in=new FileInputStream(store.mediaFile(item.optString("hash")))) {
                                int n; while((n=in.read(buffer))!=-1) { if(Thread.currentThread().isInterrupted()) throw new IOException("Yedekleme kesildi"); zip.write(buffer,0,n); }
                            }
                            zip.closeEntry(); files.put(entry);
                        }
                        manifest.put(new JSONObject().put("name",p.optString("name")).put("animated",p.optInt("animated")==1).put("files",files));
                    }
                    zip.putNextEntry(new ZipEntry("paketler.json")); zip.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                }
                runOnUiThread(()->message("Çıkartmalar ZIP olarak yedeklendi. ZIP yeniden içe alınabilir; paketler seçtiğin yeni adla oluşturulur."));
            } catch(Exception e) { runOnUiThread(()->message("Yedekleme tamamlanamadı: "+e.getMessage())); }
        });
    }
    private void thumbnail(ImageView view,File file) {
        String key=file.getAbsolutePath(); view.setTag(key); view.setImageDrawable(null);
        Bitmap cached=cache.get(key); if(cached!=null) { view.setImageBitmap(cached); return; }
        io.execute(()->{
            try {
                Bitmap decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file),(d,i,s)->{ d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE); d.setTargetSize(96,96); });
                cache.put(key,decoded);
                runOnUiThread(()->{ if(key.equals(view.getTag())&&!isDestroyed()) view.setImageBitmap(decoded); });
            } catch(Exception ignored) {}
        });
    }
    private final class PackAdapter extends BaseAdapter {
        public int getCount() { return visible.size(); }
        public Object getItem(int p) { return visible.get(p); }
        public long getItemId(int p) { return p; }
        public View getView(int position,View recycled,ViewGroup parent) {
            LinearLayout card; ImageView image; TextView label,meta; Button add;
            if(recycled==null) {
                card=row(); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(12),dp(12),dp(8),dp(12)); card.setBackground(round(CARD,16));
                image=new ImageView(MainActivity.this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); card.addView(image,new LinearLayout.LayoutParams(dp(60),dp(60)));
                LinearLayout words=column(); words.setPadding(dp(12),0,dp(8),0);
                label=text("",15,INK); label.setMaxLines(2); label.setEllipsize(TextUtils.TruncateAt.END); label.setTypeface(null,Typeface.BOLD);
                meta=text("",12,MUTED); words.addView(label); words.addView(meta); card.addView(words,new LinearLayout.LayoutParams(0,-2,1));
                add=button("+",true,null); add.setTextSize(22); add.setFocusable(false); card.addView(add,new LinearLayout.LayoutParams(dp(46),dp(46)));
                card.setTag(new Object[]{image,label,meta,add});
            } else card=(LinearLayout)recycled;
            Object[] views=(Object[])card.getTag(); image=(ImageView)views[0]; label=(TextView)views[1]; meta=(TextView)views[2]; add=(Button)views[3];
            JSONObject p=visible.get(position); label.setText(p.optString("name"));
            meta.setText(p.optInt("count")+" çıkartma · "+(p.optInt("animated")==1?"Hareketli":"Sabit")+(p.optInt("count")==1?" · Tekli":""));
            add.setEnabled(p.optInt("count")>0); add.setOnClickListener(v->addPack(p)); add.setContentDescription(p.optString("name")+" paketini WhatsApp’a ekle");
            thumbnail(image,store.trayFile(p.optString("id"))); return card;
        }
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(-1,-2); }
    private LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1); p.setMargins(0,dp(4),dp(5),0); return p; }
    private TextView text(String s,int size,int color) { TextView t=new TextView(this); t.setText(s); t.setTextColor(color); t.setTextSize(size); return t; }
    private Button button(String s,boolean primary,View.OnClickListener listener) {
        Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(13); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(8),0,dp(8),0);
        b.setTextColor(primary?BG:LIME); b.setBackground(round(primary?LIME:0xff26382b,12)); b.setOnClickListener(listener); return b;
    }
    private GradientDrawable round(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private void message(String s) { if(!isFinishing()&&!isDestroyed()) Toast.makeText(this,s==null?"İşlem tamamlanamadı":s,Toast.LENGTH_LONG).show(); }
}
