package dev.ferro.seed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
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

public final class MainActivity extends Activity {
    private static final int BG=0xFF181818, PANEL=0xFF242424, BORDER=0xFF606060,
            TEXT=0xFFF5F5F5, MUTED=0xFFB8B8B8, ACCENT=0xFFEEEEEE, DARK=0xFF181818;
    private static final int EXPORT_REQUEST=41;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final SecureRandom random=new SecureRandom();
    private final TonePlayer player=new TonePlayer();
    private HistoryStore store;
    private NpuEngine engine;
    private SharedPreferences preferences;
    private NoteRecord current, pendingExport;
    private boolean generating, ready, initializing, destroyed, playing;
    private String engineMessage="생성기를 준비하고 있어요.", modelVersion="unknown-model";
    private String page="home";
    private long playbackToken;
    private long selectionVersion;
    private LinearLayout root;

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

    private void generate(){
        if(generating)return;
        if(!ready){page="settings";showPage();return;}
        stopPlayback();generating=true;showHome();
        final long selectionAtStart=selectionVersion;
        long seed=random.nextLong();
        int count=preferences.getInt("count",4);
        double exploration=preferences.getInt("exploration",55)/100.0;
        int range=preferences.getInt("range",1);
        int low=range==1?48:36, high=range==0?71:range==1?83:99;
        worker.execute(()->{
            try{
                long started=android.os.SystemClock.elapsedRealtime();
                int[] notes=ChordSampler.generate(engine::infer,seed,count,exploration,low,high);
                SoundSampler.Result sound=SoundSampler.generate(engine::inferSound,
                        seed^0x464552524F4D4353L,notes,exploration);
                NoteRecord result=new NoteRecord(UUID.randomUUID().toString(),System.currentTimeMillis(),
                        notes,sound.velocities,sound.tones,sound.decays,sound.drives,false,seed,modelVersion);
                store.add(result);
                android.util.Log.i("SoriGeneration","saved="+result.id+" backend=QNN_HTP elapsedMs="+
                        (android.os.SystemClock.elapsedRealtime()-started)+" notes="+Arrays.toString(notes));
                runOnUiThread(()->{
                    if(destroyed)return;
                    if(selectionVersion==selectionAtStart){current=result;selectionVersion++;}
                    generating=false;
                    if(page.equals("home"))showHome();
                    else if(page.equals("history"))showHistory(null);
                });
            }catch(Exception failure){
                android.util.Log.e("SoriGeneration","Generation failed; no substitute output",failure);
                runOnUiThread(()->{
                    if(destroyed)return;
                    generating=false;ready=engine!=null&&engine.initialized();engineMessage=failure.getMessage();
                    showPage();
                    new AlertDialog.Builder(this).setTitle("음을 생성하지 못했어요")
                            .setMessage("NPU 실행과 저장 상태를 확인해 주세요. 기존 생성 기록은 그대로 있어요.\n\n"+failure.getMessage())
                            .setPositiveButton("확인",null).show();
                });
            }
        });
    }

    private void play(NoteRecord record){
        if(record==null)return;
        current=record;selectionVersion++;playing=true;long token=++playbackToken;
        if(page.equals("home"))showHome();
        player.play(record,preferences.getInt("volume",45)/100.0,()->runOnUiThread(()->{
                    if(destroyed || token!=playbackToken)return;
                    playing=false;if(page.equals("home"))showHome();
                }),error->runOnUiThread(()->{if(!destroyed && token==playbackToken)toast("재생하지 못했어요: "+error);}));
    }

    private void stopPlayback(){player.stop();playing=false;playbackToken++;}

    private void like(NoteRecord record){
        worker.execute(()->{
            try{
                NoteRecord updated=store.toggleLike(record.id);
                runOnUiThread(()->{
                    if(destroyed)return;
                    if(current!=null && current.id.equals(record.id))current=updated;
                    if(page.equals("history"))showHistory(record.id);else showPage();
                });
            }catch(Exception e){runOnUiThread(()->showError("좋아요를 저장하지 못했어요",e));}
        });
    }

    private void delete(NoteRecord record){
        new AlertDialog.Builder(this).setTitle("이 소리를 삭제할까요?")
                .setMessage(ChordSampler.describe(record.pitches)+"\n생성 기록과 좋아요 목록에서 삭제돼요.")
                .setNegativeButton("취소",null).setPositiveButton("삭제",(d,w)->{
                    worker.execute(()->{
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
        worker.execute(()->{
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
        base("home");
        LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);
        TextView word=text("FerroMC",28,TEXT,true);word.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(word,new LinearLayout.LayoutParams(0,dp(52),1));
        TextView status=text(ready?"준비 완료":initializing?"준비 중":"준비 필요",12,MUTED,false);
        status.setGravity(Gravity.CENTER);status.setPadding(dp(12),0,0,0);
        status.setOnClickListener(v->{page="settings";showPage();});status.setContentDescription("생성기 상태");top.addView(status,new LinearLayout.LayoutParams(-2,dp(48)));
        root.addView(top);
        View topRule=new View(this);topRule.setBackgroundColor(BORDER);root.addView(topRule,new LinearLayout.LayoutParams(-1,dp(1)));

        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=column();body.setGravity(Gravity.CENTER);body.setPadding(0,dp(28),0,dp(28));
        TextView caption=text("현재 음",13,MUTED,true);caption.setGravity(Gravity.CENTER);body.addView(caption);
        TextView title=text(generating?"생성 중…":current==null?"생성한 음 없음":ChordSampler.describe(current.pitches),24,TEXT,true);
        title.setPadding(0,dp(12),0,0);
        title.setGravity(Gravity.CENTER);body.addView(title);
        TextView subtitle=text(current==null?"음을 생성하면 여기에 표시됩니다.":date(current.createdAt),12,MUTED,false);
        subtitle.setGravity(Gravity.CENTER);LinearLayout.LayoutParams subtitleSize=new LinearLayout.LayoutParams(-1,-2);subtitleSize.topMargin=dp(9);body.addView(subtitle,subtitleSize);

        Button generate=button(generating?"생성 중…":"음 생성",true,27);
        generate.setContentDescription("음 생성");generate.setEnabled(!generating && ready);generate.setAlpha(generate.isEnabled()?1f:.48f);
        generate.setOnClickListener(v->generate());LinearLayout.LayoutParams generateSize=new LinearLayout.LayoutParams(-1,dp(84));generateSize.topMargin=dp(30);body.addView(generate,generateSize);
        Button replay=button(playing?"재생 멈추기":"재생",false,27);replay.setContentDescription(playing?"재생 멈추기":"재생");replay.setEnabled(current!=null);replay.setAlpha(current==null?.4f:1f);
        replay.setOnClickListener(v->{if(playing){stopPlayback();showHome();}else play(current);});LinearLayout.LayoutParams replaySize=new LinearLayout.LayoutParams(-1,dp(84));replaySize.topMargin=dp(12);body.addView(replay,replaySize);

        if(current!=null){
            LinearLayout actions=row();Button heart=smallButton(current.liked?"♥  좋아요":"♡  좋아요");heart.setContentDescription("현재 음 좋아요");heart.setTextColor(current.liked?ACCENT:MUTED);heart.setOnClickListener(v->like(current));
            actions.addView(heart,new LinearLayout.LayoutParams(0,dp(46),1));
            if(current.liked){Button save=smallButton(".ferro 내보내기");save.setOnClickListener(v->export(current));actions.addView(save,new LinearLayout.LayoutParams(0,dp(46),1));}
            body.addView(actions);
            body.addView(soundTable(current));
        }
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        View bottomRule=new View(this);bottomRule.setBackgroundColor(BORDER);root.addView(bottomRule,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout nav=row();Button settings=smallButton("설정");settings.setContentDescription("설정");settings.setOnClickListener(v->{page="settings";showPage();});
        Button history=smallButton("과거 생성기록 보기");history.setContentDescription("과거 생성기록 보기");history.setOnClickListener(v->showHistory(null));
        nav.addView(settings,new LinearLayout.LayoutParams(0,dp(52),1));nav.addView(history,new LinearLayout.LayoutParams(0,dp(52),2));root.addView(nav);
    }

    private void showHistory(String keepId){
        base("history");header("생성 기록");
        List<NoteRecord> records=store.sortedHistory();
        long liked=records.stream().filter(r->r.liked).count();
        TextView intro=text("좋아요는 위에, 최근 기록은 아래에 표시됩니다.",12,MUTED,false);intro.setPadding(0,dp(8),0,dp(16));root.addView(intro);
        LinearLayout links=row();Button likedLink=smallButton("♥ 좋아요 "+liked);Button recentLink=smallButton("최근 생성 "+(records.size()-liked));
        links.addView(likedLink,new LinearLayout.LayoutParams(0,dp(44),1));links.addView(recentLink,new LinearLayout.LayoutParams(0,dp(44),1));root.addView(links);
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setContentDescription("생성 기록 목록");
        LinearLayout list=column();list.setPadding(0,dp(12),0,dp(24));scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView likedHeader=section("좋아요",liked==0?"좋아요를 누른 음이 없습니다.":"다시 듣거나 .ferro 파일로 저장할 수 있습니다.");list.addView(likedHeader);
        HashMap<String,View> cards=new HashMap<>();
        for(NoteRecord record:records)if(record.liked){View card=recordCard(record);cards.put(record.id,card);list.addView(card,cardSpacing());}
        TextView recentHeader=section("최근 생성","최신 기록부터 표시합니다.");recentHeader.setContentDescription("최근 생성 시작");list.addView(recentHeader);
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
    @Override protected void onStop(){super.onStop();stopPlayback();}
    @Override protected void onResume(){super.onResume();if(store!=null && page.equals("home"))showHome();}
    @Override protected void onDestroy(){destroyed=true;player.close();worker.execute(()->{if(engine!=null)engine.close();});worker.shutdown();super.onDestroy();}

}
