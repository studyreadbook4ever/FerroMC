package dev.ferro.seed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int BG=0xFF181818, PANEL=0xFF242424, BORDER=0xFF606060,
            TEXT=0xFFF5F5F5, MUTED=0xFFB8B8B8, ACCENT=0xFFEEEEEE, DARK=0xFF181818;
    private static final int EXPORT_REQUEST=41;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    // Disk writes and like taps never wait behind HTP inference. Only worker owns the engine.
    private final ExecutorService historyWorker=Executors.newSingleThreadExecutor();
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private final AtomicLong generationToken=new AtomicLong();
    private final SecureRandom random=new SecureRandom();
    private final TonePlayer player=new TonePlayer();
    private HistoryStore store;
    private NpuEngine engine;
    private SharedPreferences preferences;
    private NoteRecord current, pendingExport;
    private boolean generating, ready, initializing, playing, generationIsRepeat;
    private volatile boolean destroyed;
    private String engineMessage="생성기를 준비하고 있어요.", modelVersion="unknown-model";
    private String page="home";
    private long playbackToken;
    private long selectionVersion;
    private LinearLayout root;
    private RepeatSession repeatSession;
    private LinearLayout homeRoot, homeActions, homeSound;
    private Button homeRepeatButton, homeGenerateButton, homeReplayButton, homeLikeButton, homeExportButton;
    private TextView homeTitle, homeCaption, homeSubtitle, homeProgress;
    private NoteRecord homeRecord, pressedLikeRecord;
    private String displayedSoundId;
    private final HashMap<String,Integer> pendingLikes=new HashMap<>();
    private final HashMap<String,Boolean> optimisticLikes=new HashMap<>();
    private final Runnable historyChanged=()->mainHandler.post(()->{
        if(destroyed || store==null)return;
        refreshCurrentAfterHistoryChange();
        if(page.equals("home"))showHome();
        else if(page.equals("history"))showHistory(null);
    });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences=getSharedPreferences("settings",MODE_PRIVATE);
        try {
            store=HistoryStore.shared(this);
            current=store.latest();
        } catch(Exception error) {
            new AlertDialog.Builder(this).setTitle("기록을 열 수 없어요")
                    .setMessage("기존 기록을 보존한 채 앱을 닫습니다.\n"+error.getMessage())
                    .setCancelable(false).setPositiveButton("닫기",(d,w)->finish()).show();
            return;
        }
        if(state!=null) {
            String selected=state.getString("current");
            if(selected!=null && store.get(selected)!=null)current=store.get(selected);
            page=state.getString("page","home");
            String exported=state.getString("export");
            if(exported!=null) try { pendingExport=NoteRecord.fromJson(new JSONObject(exported)); } catch(Exception ignored){}
        }
        try {
            JSONObject pitchInfo=readAssetJson("model_info.json");
            JSONObject soundInfo=readAssetJson("sound_model_info.json");
            modelVersion="pitch-"+pitchInfo.optString("model_version",pitchInfo.optString("version","1"))
                    +"/"+soundInfo.getString("model_version");
        } catch(Exception error) { android.util.Log.e("FerroMC", "Model metadata unavailable", error); }
        repeatSession=new RepeatSession((delay,action)->{
            mainHandler.postDelayed(action,delay);
            return ()->mainHandler.removeCallbacks(action);
        },new RepeatSession.Listener(){
            @Override public void generate(RepeatSession.Generation request){submitGeneration(request);}
            @Override public void play(NoteRecord record,Runnable finished,Consumer<Exception> failed){
                playAutomatic(record,finished,failed);
            }
            @Override public void stopPlayback(){MainActivity.this.stopPlayback();}
            @Override public void changed(){
                if(!repeatSession.isRunning() && generationIsRepeat){
                    generationToken.incrementAndGet();generating=false;generationIsRepeat=false;
                }
                if(repeatSession.isRunning())getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if(!destroyed && page.equals("home"))showHome();
            }
            @Override public void failed(Exception error){showError("반복 재생을 멈췄어요",error);}
        });
        store.addChangeListener(historyChanged);
        // Close the read/register gap if an old Activity completed a like during recreation.
        refreshCurrentAfterHistoryChange();
        showPage();
        initializeEngine();
        if(store.recoveredFromBackup())toast("이전 백업에서 생성 기록을 복구했어요.");
    }

    private JSONObject readAssetJson(String name) throws Exception {
        try(java.io.InputStream stream=getAssets().open(name)) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            byte[] buffer=new byte[4096];int read;
            while((read=stream.read(buffer))!=-1)bytes.write(buffer,0,read);
            return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        }
    }

    private void initializeEngine(){
        if(initializing || destroyed)return;
        initializing=true;ready=false;engineMessage="생성기를 준비하고 있어요.";
        if(!page.equals("history"))showPage();
        worker.execute(()->{
            if(destroyed)return;
            try {
                if(engine!=null)engine.close();
                engine=new NpuEngine(getApplicationContext());
                engine.initialize();
                runOnUiThread(()->{
                    if(destroyed)return;
                    ready=true;initializing=false;engineMessage=engine.status();
                    if(!page.equals("history"))showPage();
                });
            }catch(Throwable failure){
                android.util.Log.e("SoriNpu","NPU initialization failed; no CPU fallback",failure);
                String message=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
                runOnUiThread(()->{
                    if(destroyed)return;
                    ready=false;initializing=false;engineMessage=message;
                    if(!page.equals("history"))showPage();
                });
            }
        });
    }

    private boolean repeating(){return repeatSession!=null && repeatSession.isRunning();}

    private void toggleRepeat(){
        if(repeating()){repeatSession.stop();return;}
        if(ready && !generating && !destroyed){stopPlayback();repeatSession.start();}
    }

    private void stopRepeat(){if(repeatSession!=null)repeatSession.stop();}

    private void generate(){
        if(generating || repeating())return;
        if(!ready){showSettings();return;}
        submitGeneration(null);
    }

    private boolean acceptsGeneration(long token,RepeatSession.Generation request){
        return !destroyed && generationToken.get()==token && (request==null || request.isActive());
    }

    private void submitGeneration(RepeatSession.Generation request){
        if(destroyed || (request!=null && !request.isActive()))return;
        if(!ready){
            if(request!=null)request.failed(new IllegalStateException("NPU 생성기가 준비되지 않았어요."));
            return;
        }
        stopPlayback();generating=true;generationIsRepeat=request!=null;
        final long token=generationToken.incrementAndGet();
        final long selectionAtStart=selectionVersion;
        final long seed=random.nextLong();
        final int count=preferences.getInt("count",4);
        final double exploration=preferences.getInt("exploration",55)/100.0;
        final int range=preferences.getInt("range",1);
        final int low=range==1?48:36, high=range==0?71:range==1?83:99;
        if(page.equals("home"))showHome();
        worker.execute(()->{
            if(!acceptsGeneration(token,request))return;
            try{
                long started=android.os.SystemClock.elapsedRealtime();
                int[] notes=ChordSampler.generate(input->{
                    if(!acceptsGeneration(token,request))throw new java.util.concurrent.CancellationException();
                    return engine.infer(input);
                },seed,count,exploration,low,high);
                if(!acceptsGeneration(token,request))return;
                SoundSampler.Result sound=SoundSampler.generate(input->{
                    if(!acceptsGeneration(token,request))throw new java.util.concurrent.CancellationException();
                    return engine.inferSound(input);
                },seed^0x464552524F4D4353L,notes,exploration);
                if(!acceptsGeneration(token,request))return;
                NoteRecord result=new NoteRecord(UUID.randomUUID().toString(),System.currentTimeMillis(),
                        notes,sound.velocities,sound.tones,sound.decays,sound.drives,false,seed,modelVersion);
                // Generated records and all like/delete writes share one ordered storage queue.
                historyWorker.execute(()->{
                    if(!acceptsGeneration(token,request))return;
                    try{
                        // A durable write already begun may finish after Stop; its UI/audio result
                        // is still rejected below. Never block the main thread on a disk-write lock.
                        store.add(result);
                        NoteRecord saved=store.get(result.id);
                        if(saved==null)throw new java.io.IOException("생성한 음을 기록에서 찾지 못했어요.");
                        android.util.Log.i("SoriGeneration","saved="+result.id+" backend=QNN_HTP elapsedMs="+
                                (android.os.SystemClock.elapsedRealtime()-started)+" notes="+Arrays.toString(notes));
                        runOnUiThread(()->{
                            if(!acceptsGeneration(token,request))return;
                            generating=false;generationIsRepeat=false;
                            if(request!=null || selectionVersion==selectionAtStart){current=saved;selectionVersion++;}
                            refreshCurrentAfterHistoryChange();
                            if(page.equals("home"))showHome();
                            else if(page.equals("history"))showHistory(null);
                            if(request!=null)request.succeeded(saved);
                        });
                    }catch(Exception failure){generationFailed(token,request,failure);}
                });
            }catch(Exception failure){generationFailed(token,request,failure);}
        });
    }

    private void generationFailed(long token,RepeatSession.Generation request,Exception failure){
        if(!acceptsGeneration(token,request))return;
        android.util.Log.e("SoriGeneration","Generation failed; no substitute output",failure);
        runOnUiThread(()->{
            if(!acceptsGeneration(token,request))return;
            generating=false;generationIsRepeat=false;
            engineMessage=failure.getMessage();
            if(request!=null)request.failed(failure);
            else {showPage();showError("음을 생성하지 못했어요",failure);}
        });
    }

    private void play(NoteRecord record){
        if(record==null || destroyed)return;
        stopRepeat();
        current=record;selectionVersion++;
        playAutomatic(record,()->{},error->toast("재생하지 못했어요: "+error.getMessage()));
    }

    private void playAutomatic(NoteRecord record,Runnable finished,Consumer<Exception> failed){
        if(destroyed)return;
        playing=true;long token=++playbackToken;
        if(page.equals("home"))showHome();
        player.play(record,preferences.getInt("volume",45)/100.0,()->runOnUiThread(()->{
            if(destroyed || token!=playbackToken)return;
            playing=false;if(page.equals("home"))showHome();
            finished.run();
        }),error->runOnUiThread(()->{
            if(destroyed || token!=playbackToken)return;
            // TonePlayer reports finished in finally, even after a failure or cancellation.
            playbackToken++;playing=false;
            if(page.equals("home"))showHome();
            failed.accept(new IllegalStateException(error));
        }));
    }

    private void stopPlayback(){player.stop();playing=false;playbackToken++;}

    private void like(NoteRecord record){
        if(record==null || destroyed)return;
        final String id=record.id;
        NoteRecord latest=store.get(id);
        boolean liked=optimisticLikes.getOrDefault(id,latest!=null?latest.liked:record.liked);
        optimisticLikes.put(id,!liked);
        pendingLikes.put(id,pendingLikes.getOrDefault(id,0)+1);
        if(page.equals("home"))showHome();
        historyWorker.execute(()->{
            try{
                NoteRecord updated=store.toggleLike(id);
                runOnUiThread(()->{
                    if(destroyed)return;
                    finishLike(id);
                    refreshCurrentAfterHistoryChange();
                    if(updated==null)toast("최근 100개보다 오래된 기록을 정리했어요.");
                    if(page.equals("history"))showHistory(id);else if(page.equals("home"))showHome();
                });
            }catch(Exception error){runOnUiThread(()->{
                if(destroyed)return;
                finishLike(id);refreshCurrentAfterHistoryChange();
                if(page.equals("home"))showHome();
                showError("좋아요를 저장하지 못했어요",error);
            });}
        });
    }

    private void finishLike(String id){
        int left=pendingLikes.getOrDefault(id,1)-1;
        if(left<=0){pendingLikes.remove(id);optimisticLikes.remove(id);}
        else pendingLikes.put(id,left);
    }

    private void refreshCurrentAfterHistoryChange(){
        if(current==null){current=store.latest();return;}
        NoteRecord saved=store.get(current.id);
        if(saved==null){stopPlayback();current=store.latest();selectionVersion++;}
        else current=saved;
    }

    private void delete(NoteRecord record){
        new AlertDialog.Builder(this).setTitle("이 소리를 삭제할까요?")
                .setMessage(ChordSampler.describe(record.pitches)+"\n생성 기록과 좋아요 목록에서 삭제돼요.")
                .setNegativeButton("취소",null).setPositiveButton("삭제",(d,w)->{
                    historyWorker.execute(()->{
                        try{
                            store.delete(record.id);
                            runOnUiThread(()->{
                                if(destroyed)return;
                                if(current!=null && current.id.equals(record.id)){
                                    stopPlayback();current=store.latest();selectionVersion++;
                                }
                                if(page.equals("history"))showHistory(null);
                                else if(page.equals("home"))showHome();
                            });
                        }catch(Exception e){runOnUiThread(()->showError("삭제하지 못했어요",e));}
                    });
                }).show();
    }

    private void export(NoteRecord record){
        if(record==null || !record.liked){toast("좋아요를 누른 소리를 내보낼 수 있어요.");return;}
        stopRepeat();stopPlayback();
        pendingExport=record.copy();
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE,"FerroMC-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US)
                        .format(new Date(record.createdAt))+".ferro");
        startActivityForResult(intent,EXPORT_REQUEST);
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=EXPORT_REQUEST)return;
        NoteRecord record=pendingExport;pendingExport=null;
        if(result!=RESULT_OK || data==null || data.getData()==null || record==null)return;
        android.net.Uri destination=data.getData();
        historyWorker.execute(()->{
            try{
                try(OutputStream output=getContentResolver().openOutputStream(destination,"wt")){
                    if(output==null)throw new java.io.IOException("파일을 열 수 없습니다.");
                    output.write(FerroExporter.export(record,120).getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                runOnUiThread(()->toast(".ferro 파일을 저장했어요."));
            }catch(Exception e){runOnUiThread(()->showError("내보내지 못했어요",e));}
        });
    }

    private void showPage(){if(destroyed || store==null)return;switch(page){case "history":showHistory(null);break;case "settings":showSettings();break;default:showHome();}}

    private LinearLayout base(String name){
        if(!name.equals("home"))stopRepeat();
        page=name;
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        root.setPadding(dp(24),dp(14),dp(24),dp(18));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(dp(24)+bars.left,dp(14)+bars.top,dp(24)+bars.right,dp(18)+bars.bottom);return insets;
        });
        setContentView(root);root.requestApplyInsets();return root;
    }

    private void showHome(){
        if(destroyed)return;
        if(page.equals("home") && homeRoot==root && homeLikeButton!=null){bindHome();return;}
        base("home");homeRoot=root;displayedSoundId=null;pressedLikeRecord=null;
        LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);
        TextView word=text("FerroMC",28,TEXT,true);word.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(word,new LinearLayout.LayoutParams(0,dp(52),1));
        homeRepeatButton=button("100회 재생",false,14);
        homeRepeatButton.setOnClickListener(v->toggleRepeat());
        top.addView(homeRepeatButton,new LinearLayout.LayoutParams(dp(112),dp(48)));
        root.addView(top);
        View topRule=new View(this);topRule.setBackgroundColor(BORDER);root.addView(topRule,new LinearLayout.LayoutParams(-1,dp(1)));

        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=column();body.setGravity(Gravity.CENTER);body.setPadding(0,dp(28),0,dp(28));
        homeProgress=text("",13,ACCENT,true);homeProgress.setGravity(Gravity.CENTER);
        homeProgress.setContentDescription("반복 진행");homeProgress.setPadding(0,0,0,dp(14));body.addView(homeProgress);
        homeCaption=text("현재 음",13,MUTED,true);homeCaption.setGravity(Gravity.CENTER);body.addView(homeCaption);
        homeTitle=text("",24,TEXT,true);homeTitle.setPadding(0,dp(12),0,0);
        homeTitle.setGravity(Gravity.CENTER);body.addView(homeTitle);
        homeSubtitle=text("",12,MUTED,false);homeSubtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleSize=new LinearLayout.LayoutParams(-1,-2);subtitleSize.topMargin=dp(9);body.addView(homeSubtitle,subtitleSize);

        homeGenerateButton=button("음 생성",true,27);homeGenerateButton.setContentDescription("음 생성");
        homeGenerateButton.setOnClickListener(v->generate());
        LinearLayout.LayoutParams generateSize=new LinearLayout.LayoutParams(-1,dp(84));generateSize.topMargin=dp(30);body.addView(homeGenerateButton,generateSize);
        homeReplayButton=button("재생",false,27);
        homeReplayButton.setOnClickListener(v->{if(playing){stopPlayback();showHome();}else play(homeRecord);});
        LinearLayout.LayoutParams replaySize=new LinearLayout.LayoutParams(-1,dp(84));replaySize.topMargin=dp(12);body.addView(homeReplayButton,replaySize);

        homeActions=row();homeLikeButton=smallButton("♡  좋아요");homeLikeButton.setContentDescription("현재 음 좋아요");
        rememberLikeTouch();
        homeLikeButton.setOnClickListener(v->{
            NoteRecord pressed=pressedLikeRecord!=null?pressedLikeRecord:homeRecord;
            pressedLikeRecord=null;like(pressed);
        });
        homeActions.addView(homeLikeButton,new LinearLayout.LayoutParams(0,dp(46),1));
        homeExportButton=smallButton(".ferro 내보내기");
        homeExportButton.setOnClickListener(v->export(homeRecord));
        homeActions.addView(homeExportButton,new LinearLayout.LayoutParams(0,dp(46),1));body.addView(homeActions);
        homeSound=column();body.addView(homeSound);
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        View bottomRule=new View(this);bottomRule.setBackgroundColor(BORDER);root.addView(bottomRule,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout nav=row();Button settings=smallButton("설정");settings.setContentDescription("설정");settings.setOnClickListener(v->showSettings());
        Button history=smallButton("과거 생성기록 보기");history.setContentDescription("과거 생성기록 보기");history.setOnClickListener(v->showHistory(null));
        nav.addView(settings,new LinearLayout.LayoutParams(0,dp(52),1));nav.addView(history,new LinearLayout.LayoutParams(0,dp(52),2));root.addView(nav);
        bindHome();
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void rememberLikeTouch(){
        // Observe DOWN only: returning false keeps Button's native click/accessibility behavior.
        homeLikeButton.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN)pressedLikeRecord=homeRecord==null?null:homeRecord.copy();
            else if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)pressedLikeRecord=null;
            return false;
        });
    }

    private void bindHome(){
        // Preserve Views and in-flight touch gestures through generation/audio/like callbacks.
        boolean running=repeating(), hasRecord=current!=null;
        homeRecord=hasRecord?current.copy():null;
        homeRepeatButton.setText(running?"중단":"100회 재생");
        homeRepeatButton.setContentDescription(running?"반복 중단":"100회 재생");
        enabled(homeRepeatButton,running || (ready && !generating));
        String progress="";
        if(running){
            progress=repeatSession.cycle()+"/100 · "+(generating || repeatSession.phase()==RepeatSession.Phase.GENERATING
                    ?"음 생성 중":repeatSession.playNumber()+"/3 재생"+(playing?" 중":" · 대기"));
        }else if(!ready)progress=initializing?"생성기 준비 중":"설정에서 생성기 상태를 확인해 주세요.";
        homeProgress.setText(progress);homeProgress.setVisibility(progress.isEmpty()?View.GONE:View.VISIBLE);
        homeCaption.setText(generating?"현재 음 · 다음 음 생성 중":"현재 음");
        homeGenerateButton.setText(generating?"생성 중…":"음 생성");enabled(homeGenerateButton,!generating && ready && !running);
        homeReplayButton.setText(playing?"재생 멈추기":"재생");
        homeReplayButton.setContentDescription(playing?"재생 멈추기":"재생");enabled(homeReplayButton,hasRecord && !running);
        homeActions.setVisibility(hasRecord?View.VISIBLE:View.GONE);enabled(homeLikeButton,hasRecord);
        boolean liked=hasRecord && optimisticLikes.getOrDefault(current.id,current.liked);
        homeLikeButton.setText(liked?"♥  좋아요":"♡  좋아요");homeLikeButton.setTextColor(liked?ACCENT:MUTED);
        // Reserve the action space so a heart press cannot move when its like is saved.
        homeExportButton.setVisibility(liked?View.VISIBLE:View.INVISIBLE);
        enabled(homeExportButton,hasRecord && current.liked && !pendingLikes.containsKey(current.id));
        String id=hasRecord?current.id:null;
        if(!java.util.Objects.equals(displayedSoundId,id) || homeTitle.length()==0){
            homeTitle.setText(hasRecord?ChordSampler.describe(current.pitches):"생성한 음 없음");
            homeSubtitle.setText(hasRecord?date(current.createdAt):"음을 생성하면 여기에 표시됩니다.");
            homeSound.removeAllViews();if(hasRecord)homeSound.addView(soundTable(current));displayedSoundId=id;
        }
    }

    private void enabled(Button button,boolean enabled){button.setEnabled(enabled);button.setAlpha(enabled?1f:.45f);}

    private void showHistory(String keepId){
        base("history");header("생성 기록");
        List<NoteRecord> records=store.sortedHistory();
        long liked=records.stream().filter(r->r.liked).count();
        TextView intro=text("좋아요는 따로 보관하고, 최근 생성은 100개까지 저장합니다.",12,MUTED,false);intro.setPadding(0,dp(8),0,dp(16));root.addView(intro);
        LinearLayout links=row();Button likedLink=smallButton("♥ 좋아요 "+liked);Button recentLink=smallButton("최근 생성 "+(records.size()-liked));
        links.addView(likedLink,new LinearLayout.LayoutParams(0,dp(44),1));links.addView(recentLink,new LinearLayout.LayoutParams(0,dp(44),1));root.addView(links);
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setContentDescription("생성 기록 목록");
        LinearLayout list=column();list.setPadding(0,dp(12),0,dp(24));scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView likedHeader=section("좋아요",liked==0?"좋아요를 누른 음이 없습니다.":"다시 듣거나 .ferro 파일로 저장할 수 있습니다.");list.addView(likedHeader);
        HashMap<String,View> cards=new HashMap<>();
        for(NoteRecord record:records)if(record.liked){View card=recordCard(record);cards.put(record.id,card);list.addView(card,cardSpacing());}
        TextView recentHeader=section("최근 생성","최신 100개를 보관하며, 오래된 기록은 자동 삭제됩니다.");recentHeader.setContentDescription("최근 생성 시작");list.addView(recentHeader);
        for(NoteRecord record:records)if(!record.liked){View card=recordCard(record);cards.put(record.id,card);list.addView(card,cardSpacing());}
        if(records.size()==liked){
            TextView empty=text(records.isEmpty()?"생성 기록이 없습니다.":"좋아요 이외의 기록이 없습니다.",16,MUTED,false);
            empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(40),0,dp(20));list.addView(empty);
            Button start=button("음 생성 화면으로",false,15);start.setOnClickListener(v->showHome());list.addView(start,new LinearLayout.LayoutParams(-1,dp(52)));
        }
        likedLink.setOnClickListener(v->scroll.smoothScrollTo(0,likedHeader.getTop()));
        recentLink.setOnClickListener(v->scroll.smoothScrollTo(0,recentHeader.getTop()));
        View anchor=keepId!=null&&cards.containsKey(keepId)?cards.get(keepId):recentHeader;
        View tailSpacer=new View(this);list.addView(tailSpacer,new LinearLayout.LayoutParams(-1,0));
        // Equivalent to a document fragment anchor: favorites are above, initial view is recent.
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener(){
            @Override public void onGlobalLayout(){
                int recentHeight=list.getHeight()-recentHeader.getTop()-tailSpacer.getHeight();
                int needed=Math.max(0,scroll.getHeight()-recentHeight);
                if(tailSpacer.getLayoutParams().height!=needed){
                    tailSpacer.setLayoutParams(new LinearLayout.LayoutParams(-1,needed));return;
                }
                scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);scroll.scrollTo(0,anchor.getTop());
            }
        });
    }

    private View recordCard(NoteRecord record){
        LinearLayout card=column();card.setTag(record.id);card.setBackground(shape(PANEL,2,BORDER));card.setPadding(dp(16),dp(16),dp(16),dp(10));
        TextView time=text(date(record.createdAt)+(record.liked?"   ♥":""),11,record.liked?ACCENT:MUTED,false);card.addView(time);
        TextView notes=text(ChordSampler.describe(record.pitches),20,TEXT,true);notes.setPadding(0,dp(9),0,dp(12));card.addView(notes);
        card.addView(soundTable(record));
        LinearLayout actions=row();Button replay=smallButton("재생");replay.setContentDescription("기록 재생 "+record.id);replay.setOnClickListener(v->play(record));
        Button heart=smallButton(record.liked?"♥ 좋아요":"♡ 좋아요");heart.setContentDescription("기록 좋아요 "+record.id);heart.setTextColor(record.liked?ACCENT:MUTED);heart.setOnClickListener(v->like(record));
        Button remove=smallButton("삭제");remove.setContentDescription("기록 삭제 "+record.id);remove.setOnClickListener(v->delete(record));
        actions.addView(replay,new LinearLayout.LayoutParams(0,dp(44),1));actions.addView(heart,new LinearLayout.LayoutParams(0,dp(44),1.2f));actions.addView(remove,new LinearLayout.LayoutParams(0,dp(44),.8f));card.addView(actions);
        if(record.liked){Button save=smallButton(".ferro 내보내기");save.setContentDescription("기록 내보내기 "+record.id);save.setTextColor(ACCENT);save.setOnClickListener(v->export(record));card.addView(save,new LinearLayout.LayoutParams(-1,dp(44)));}
        return card;
    }

    private void showSettings(){
        base("settings");header("설정");
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout body=column();body.setPadding(0,dp(18),0,dp(30));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body.addView(section("음 생성","정해진 코드 진행 없이 음을 생성합니다."));
        body.addView(label("함께 울리는 음"));body.addView(choices(new String[]{"2개","3개","4개"},preferences.getInt("count",4)-2,i->preferences.edit().putInt("count",i+2).apply()));
        body.addView(label("음역"));body.addView(choices(new String[]{"낮게","중간","넓게"},preferences.getInt("range",1),i->preferences.edit().putInt("range",i).apply()));
        body.addView(label("탐색 폭"));body.addView(slider("exploration",55,0,100,"차분한 변화","넓은 변화"));
        body.addView(section("재생","앱에서 들을 때 적용됩니다."));
        body.addView(label("재생 음량"));body.addView(slider("volume",45,10,80,"작게","크게"));
        TextView timbre=text("TONE · DECAY · DRIVE · VELOCITY는 음마다 생성해 기록에 저장합니다.",12,MUTED,false);
        timbre.setPadding(0,dp(18),0,0);body.addView(timbre);
        body.addView(section("생성기 상태",ready?"준비 완료":initializing?"준비 중":"준비 실패"));
        TextView status=text(ready?"이 기기에서 음을 생성할 수 있습니다.":engineMessage,12,ready?ACCENT:MUTED,false);status.setTextIsSelectable(true);status.setPadding(0,dp(8),0,dp(14));body.addView(status);
        if(!ready){Button retry=button(initializing?"준비 중…":"생성기 다시 준비하기",false,15);retry.setEnabled(!initializing);retry.setOnClickListener(v->initializeEngine());body.addView(retry,new LinearLayout.LayoutParams(-1,dp(52)));}
        TextView detail=text("좋아요를 누른 음은 .ferro 파일로 저장할 수 있습니다.\nFerro에서 음표와 음색 값을 편집할 수 있습니다.",12,MUTED,false);detail.setPadding(0,dp(22),0,0);body.addView(detail);
    }

    private View soundTable(NoteRecord record){
        LinearLayout table=column();table.setContentDescription("음색 값 "+record.id);
        table.setPadding(0,dp(10),0,dp(10));
        String[] headers={"음","TONE","DECAY","DRIVE","VEL"};
        LinearLayout heading=row();
        for(String value:headers){TextView cell=text(value,11,MUTED,true);cell.setGravity(Gravity.CENTER);
            heading.addView(cell,new LinearLayout.LayoutParams(0,dp(24),1));}
        table.addView(heading);
        for(int i=0;i<record.pitches.length;i++){
            String[] values={ChordSampler.pitchName(record.pitches[i]),Integer.toString(record.tones[i]),
                    Integer.toString(record.decays[i]),Integer.toString(record.drives[i]),Integer.toString(record.velocities[i])};
            LinearLayout line=row();
            for(String value:values){TextView cell=text(value,13,TEXT,false);cell.setGravity(Gravity.CENTER);
                line.addView(cell,new LinearLayout.LayoutParams(0,dp(26),1));}
            table.addView(line);
        }
        return table;
    }

    private interface Choice {void select(int index);}
    private View choices(String[] labels,int selected,Choice choice){
        LinearLayout row=row();for(int i=0;i<labels.length;i++){
            final int index=i;Button button=button(labels[i],i==selected,14);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(46),1);if(i>0)lp.leftMargin=dp(8);row.addView(button,lp);
            button.setOnClickListener(v->{choice.select(index);showSettings();});
        }return row;
    }
    private View slider(String key,int fallback,int min,int max,String left,String right){
        LinearLayout group=column();SeekBar bar=new SeekBar(this);bar.setMax(max-min);bar.setProgress(preferences.getInt(key,fallback)-min);bar.setContentDescription(key.equals("volume")?"재생 음량":"탐색 폭");
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));bar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean from){if(from)preferences.edit().putInt(key,p+min).apply();}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        group.addView(bar,new LinearLayout.LayoutParams(-1,dp(44)));LinearLayout labels=row();TextView l=text(left,11,MUTED,false),r=text(right,11,MUTED,false);r.setGravity(Gravity.RIGHT);labels.addView(l,new LinearLayout.LayoutParams(0,-2,1));labels.addView(r,new LinearLayout.LayoutParams(0,-2,1));group.addView(labels);return group;
    }
    private void header(String title){LinearLayout bar=row();Button back=smallButton("‹  처음");back.setContentDescription("처음 화면");back.setOnClickListener(v->{stopPlayback();showHome();});bar.addView(back,new LinearLayout.LayoutParams(dp(82),dp(48)));TextView heading=text(title,23,TEXT,true);heading.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);bar.addView(heading,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(bar);}
    private TextView section(String title,String caption){TextView v=text(title+"\n"+caption,14,MUTED,false);android.text.SpannableString s=new android.text.SpannableString(v.getText());s.setSpan(new android.text.style.ForegroundColorSpan(TEXT),0,title.length(),0);s.setSpan(new android.text.style.AbsoluteSizeSpan(18,true),0,title.length(),0);s.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),0,title.length(),0);v.setText(s);v.setLineSpacing(dp(8),1);v.setPadding(0,dp(22),0,dp(16));return v;}
    private TextView label(String value){TextView v=text(value,13,TEXT,false);v.setPadding(0,dp(22),0,dp(10));return v;}
    private LinearLayout.LayoutParams cardSpacing(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);return p;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView text(String value,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");v.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));v.setIncludeFontPadding(false);return v;}
    private Button button(String value,boolean primary,int size){Button v=new Button(this);v.setText(value);v.setAllCaps(false);v.setTextSize(size);v.setTextColor(primary?DARK:TEXT);v.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));v.setBackground(shape(primary?ACCENT:PANEL,2,primary?0:BORDER));v.setMinHeight(0);v.setMinimumHeight(0);v.setPadding(dp(12),dp(8),dp(12),dp(8));v.setStateListAnimator(null);return v;}
    private Button smallButton(String value){Button b=button(value,false,13);b.setTextColor(MUTED);b.setBackground(shape(Color.TRANSPARENT,2,0));return b;}
    private GradientDrawable shape(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String date(long time){return new SimpleDateFormat("M월 d일  HH:mm",Locale.KOREAN).format(new Date(time));}
    private void toast(String message){if(!destroyed)Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private void showError(String title,Exception error){if(!destroyed)new AlertDialog.Builder(this).setTitle(title).setMessage(error.getMessage()).setPositiveButton("확인",null).show();}
    @Override public void onBackPressed(){if(!page.equals("home")){stopPlayback();showHome();}else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("page",page);if(current!=null)out.putString("current",current.id);if(pendingExport!=null)try{out.putString("export",pendingExport.toJson().toString());}catch(Exception ignored){}}
    @Override protected void onStop(){super.onStop();stopRepeat();stopPlayback();}
    @Override protected void onResume(){super.onResume();if(store!=null && page.equals("home"))showHome();}
    @Override protected void onDestroy(){
        destroyed=true;generationToken.incrementAndGet();stopRepeat();mainHandler.removeCallbacksAndMessages(null);
        if(store!=null)store.removeChangeListener(historyChanged);
        player.close();worker.execute(()->{if(engine!=null)engine.close();});worker.shutdown();
        historyWorker.shutdown();super.onDestroy();
    }

}
