package local.son.connectdiagnostics;

import local.son.connecthardware.CardBridge;
import local.son.connecthardware.RgbBridge.Channel;
import local.son.connecthardware.RgbLed;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.*;
import android.media.*;
import android.nfc.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

public final class MainActivity extends Activity implements SensorEventListener {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final JSONObject results=new JSONObject();
    private final Map<String,TextView> statuses=new HashMap<>();
    private LinearLayout content;
    private Typeface regular,bold;
    private AudioProbe audio;
    private CameraProbe camera;
    private TextureView preview;
    private Spinner cameras;
    private ProgressBar micLevel;
    private NfcAdapter nfc;
    private SensorManager sensors;
    private boolean nfcListening,hidListening,lightListening,visible;
    private int tagCount,hidEvents,hidEnters,hidGeneration,lightGeneration;
    private String[] cameraIds=new String[0];
    private Runnable pendingPermission, pendingResume;
    private String pendingExport;
    private Thread ledThread;
    private String audioTest="speaker";
    private static final int GREEN=0xff09ab49,TEXT=0xff1e1e23,MUTED=0xff66666f,BG=0xfff6f8fa;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        regular=Typeface.create("sans-serif",Typeface.NORMAL);
        bold=Typeface.create("sans-serif",Typeface.BOLD);
        if(state!=null){pendingExport=state.getString("export");try{JSONObject saved=new JSONObject(state.getString("results","{}"));Iterator<String> it=saved.keys();while(it.hasNext()){String k=it.next();results.put(k,saved.get(k));}}catch(Exception ignored){}}
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(18),dp(20),dp(24));scroll.addView(content);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        text(content,"커넥트 진단",26,true);
        LinearLayout controls=row(content);button(controls,"모두 중지",()->stopTests());button(controls,"보고서 복사",()->copyReport());button(controls,"저장",()->saveReport());
        sectionDevice();sectionAudio();sectionCamera();sectionNfc();sectionCard();sectionSensors();sectionLed();
        LinearLayout payment=section("결제 모듈","payment");text(payment,"설치된 순정 앱과 모듈 접근 상태를 확인합니다. 결제 승인·취소·키 다운로드·단말 등록은 실행하지 않습니다.",13,false);
        button(payment,"모듈 상태 확인",()->inspectPayment());
        audio=new AudioProbe(this,new AudioProbe.Listener(){public void onStatus(String s){status(audioTest,s);}public void onLevel(int p){if(micLevel!=null)micLevel.setProgress(p);}});
        camera=new CameraProbe(this,preview,new CameraProbe.Listener(){public void onStatus(String s){status("camera",s);}public void onFaces(int n){status("faces","현재 감지된 얼굴 "+n+"개 · 본인 인증 결과 아님");}});
        cameraIds=camera.cameraIds();cameras.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,cameraIds.length==0?new String[]{"사용 가능한 카메라 없음"}:cameraIds));
        sensors=(SensorManager)getSystemService(SENSOR_SERVICE);nfc=NfcAdapter.getDefaultAdapter(this);
        if(cameraIds.length==0)status("camera","Android Camera2에 노출된 카메라 없음");
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(LinearLayout parent,String s,int size,boolean heavy){TextView t=new TextView(this);t.setText(s);t.setTextColor(heavy?TEXT:MUTED);t.setTextSize(size);t.setTypeface(heavy?bold:regular);t.setPadding(0,dp(5),0,dp(7));parent.addView(t);return t;}
    private LinearLayout section(String title,String key){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(14),dp(18),dp(16));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(20));box.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);content.addView(box,p);text(box,title,20,true);TextView status=text(box,"테스트 전",13,false);statuses.put(key,status);return box;}
    private LinearLayout row(LinearLayout box){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);box.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;}
    private Button button(LinearLayout box,String title,Runnable run){Button b=new Button(this);b.setText(title);b.setTextSize(13);b.setTypeface(bold);b.setTextColor(TEXT);b.setAllCaps(false);b.setMinHeight(dp(48));GradientDrawable d=new GradientDrawable();d.setColor(0xffdefae4);d.setCornerRadius(dp(12));b.setBackground(d);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(box.getOrientation()==LinearLayout.HORIZONTAL?0:-1,dp(48),box.getOrientation()==LinearLayout.HORIZONTAL?1:0);p.setMargins(dp(3),dp(6),dp(3),dp(3));box.addView(b,p);b.setOnClickListener(v->{try{run.run();}catch(Exception e){new AlertDialog.Builder(this).setTitle("실행 실패").setMessage(e.getClass().getSimpleName()+": "+e.getMessage()).setPositiveButton("닫기",null).show();}});return b;}
    private void status(String key,String value){if(Looper.myLooper()!=Looper.getMainLooper()){ui.post(()->status(key,value));return;}if(isDestroyed())return;TextView t=statuses.get(key);if(t!=null)t.setText(value);try{JSONObject item=new JSONObject();item.put("message",value);item.put("time_ms",System.currentTimeMillis());results.put(key,item);}catch(JSONException ignored){}}
    private void permit(String permission,Runnable run){if(checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED){run.run();return;}pendingPermission=run;requestPermissions(new String[]{permission},10);}
    @Override public void onRequestPermissionsResult(int request,String[] names,int[] grants){super.onRequestPermissionsResult(request,names,grants);Runnable action=pendingPermission;pendingPermission=null;if(request==10&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED&&action!=null){if(visible)action.run();else pendingResume=action;}else Toast.makeText(this,"권한이 없어 테스트를 시작하지 않았습니다",Toast.LENGTH_LONG).show();}
    private void sectionDevice(){LinearLayout box=section("기기 정보","device");button(box,"기기 정보 조회",()->{PackageManager pm=getPackageManager();String v=Build.MANUFACTURER+" / "+Build.MODEL+"\nAndroid "+Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")\n빌드 "+Build.DISPLAY+"\n카메라 기능: "+pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)+"\n마이크 기능: "+pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)+"\nAndroid NFC 기능: "+pm.hasSystemFeature(PackageManager.FEATURE_NFC)+"\n기능 신고 여부이며 작동 확인 결과는 아닙니다.";status("device",v);});}
    private void sectionAudio(){LinearLayout box=section("스피커","speaker");text(box,"현재 미디어 음량으로 1초간 시험음을 재생합니다.",13,false);LinearLayout r=row(box);button(r,"왼쪽",()->tone(1));button(r,"양쪽",()->tone(0));button(r,"오른쪽",()->tone(2));TextView heard=text(box,"청취 확인 전",13,false);statuses.put("speaker-listening",heard);r=row(box);button(r,"들림",()->status("speaker-listening","사용자 확인: 들림"));button(r,"안 들림",()->status("speaker-listening","사용자 확인: 안 들림"));
        box=section("마이크","mic");micLevel=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);micLevel.setMax(100);box.addView(micLevel,new LinearLayout.LayoutParams(-1,dp(20)));text(box,"최대 10초간 입력 크기를 표시합니다. 음성은 저장하지 않습니다.",13,false);r=row(box);button(r,"입력 측정",()->permit(Manifest.permission.RECORD_AUDIO,()->{audioTest="mic";audio.listen();}));button(r,"오디오 중지",()->{audio.stop();micLevel.setProgress(0);status("mic","중지됨");});}
    private void tone(int channel){audioTest="speaker";AudioManager manager=(AudioManager)getSystemService(AUDIO_SERVICE);status("speaker","미디어 음량 "+manager.getStreamVolume(AudioManager.STREAM_MUSIC)+" / "+manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));audio.tone(channel);}
    private void sectionCamera(){LinearLayout box=section("카메라 / 얼굴 감지","camera");cameras=new Spinner(this);box.addView(cameras,new LinearLayout.LayoutParams(-1,dp(48)));preview=new TextureView(this);box.addView(preview,new LinearLayout.LayoutParams(-1,dp(240)));TextView f=text(box,"얼굴 감지: 테스트 전",13,false);statuses.put("faces",f);text(box,"카메라 ID를 선택해 영상과 감지 지원을 확인합니다. 영상 저장과 결제용 얼굴 인증은 하지 않습니다.",13,false);LinearLayout r=row(box);button(r,"미리보기",()->permit(Manifest.permission.CAMERA,()->{if(cameraIds.length==0){status("camera","사용 가능한 카메라 없음");return;}String id=cameraIds[cameras.getSelectedItemPosition()];status("camera",camera.describe(id));camera.start(id);}));button(r,"정보",()->{if(cameraIds.length>0)status("camera",camera.describe(cameraIds[cameras.getSelectedItemPosition()]));});button(r,"중지",()->{camera.stop();status("camera","중지됨");});}
    private void sectionNfc(){LinearLayout box=section("NFC 태그","nfc");text(box,"태그 감지 횟수와 기술 종류만 표시합니다. 카드 번호·UID·NDEF 내용은 읽거나 저장하지 않습니다.",13,false);LinearLayout r=row(box);button(r,"감지 시작",()->startNfc());button(r,"중지",()->stopNfc());button(r,"NFC 설정",()->{try{startActivity(new Intent(android.provider.Settings.ACTION_NFC_SETTINGS));}catch(ActivityNotFoundException e){status("nfc","NFC 설정 화면 없음");}});}
    private void startNfc(){if(nfc==null){status("nfc","Android NFC 어댑터 없음 · 결제 리더 NFC와 별개");return;}if(!nfc.isEnabled()){status("nfc","NFC 꺼짐");return;}tagCount=0;nfcListening=true;nfc.enableReaderMode(this,tag->{String[] tech=tag.getTechList();ui.post(()->{if(!nfcListening||!visible)return;tagCount++;status("nfc","태그 감지 "+tagCount+"회\n"+String.join("\n",tech));});},NfcAdapter.FLAG_READER_NFC_A|NfcAdapter.FLAG_READER_NFC_B|NfcAdapter.FLAG_READER_NFC_F|NfcAdapter.FLAG_READER_NFC_V|NfcAdapter.FLAG_READER_NFC_BARCODE|NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK|NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,null);status("nfc","태그 대기 중");}
    private void stopNfc(){nfcListening=false;if(nfc!=null)try{nfc.disableReaderMode(this);}catch(Exception ignored){}status("nfc","중지됨 · 감지 "+tagCount+"회");}
    private Thread icThread;
    private int icGeneration;
    private boolean icMonitoring;
    private Runnable nextIcQuery;

    private void sectionCard() {
        LinearLayout box = section("IC 카드", "ic");
        LinearLayout controls = row(box);
        button(controls, "상태 조회", () -> startIcQuery(false));
        button(controls, "감지 시작", () -> startIcQuery(true));
        button(controls, "중지", () -> stopIcQuery(true));

        box = section("HID 카드 리더", "card");
        text(box, "키보드 방식 리더의 입력 횟수를 확인합니다.", 13, false);
        controls = row(box);
        button(controls, "HID 입력 대기", () -> {
            final int current = ++hidGeneration;
            hidEvents = 0;
            hidEnters = 0;
            hidListening = true;
            status("card", "15초간 HID 입력 대기");
            ui.postDelayed(() -> {
                if (hidListening && current == hidGeneration) {
                    hidListening = false;
                    status("card", "HID 입력 " + hidEvents + "개 / 종료 키 " + hidEnters + "회");
                }
            }, 15000);
        });
        button(controls, "중지", () -> {
            hidListening = false;
            status("card", "HID 감지 중지");
        });
    }

    private void startIcQuery(boolean monitor) {
        if (icThread != null && icThread.isAlive()) {
            status("ic", "IC 상태 조회 중");
            return;
        }
        stopIcQuery(false);
        icMonitoring = monitor;
        status("ic", "IC 상태 조회 중");
        queryIc(icGeneration);
    }

    private void queryIc(int generation) {
        icThread = new Thread(() -> {
            String state;
            try {
                switch (CardBridge.presence()) {
                    case INSERTED: state = "카드 삽입됨"; break;
                    case REMOVED: state = "카드 없음"; break;
                    case UNSUPPORTED_READER: state = "지원하지 않는 리더 펌웨어"; break;
                    default: state = "리더 응답 확인 실패"; break;
                }
            } catch (IOException e) {
                state = "리더 서비스 연결 실패";
            }
            final String result = state;
            ui.post(() -> {
                if (generation != icGeneration) return;
                status("ic", result);
                if (icMonitoring) {
                    nextIcQuery = () -> queryIc(generation);
                    ui.postDelayed(nextIcQuery, 3000);
                }
            });
        }, "DiagnosticIC");
        icThread.start();
    }

    private void stopIcQuery(boolean report) {
        icGeneration++;
        icMonitoring = false;
        if (nextIcQuery != null) ui.removeCallbacks(nextIcQuery);
        if (report) status("ic", "IC 감지 중지");
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e){if(hidListening&&e.getAction()==KeyEvent.ACTION_DOWN&&e.getKeyCode()!=KeyEvent.KEYCODE_BACK&&e.getKeyCode()!=KeyEvent.KEYCODE_VOLUME_UP&&e.getKeyCode()!=KeyEvent.KEYCODE_VOLUME_DOWN){hidEvents++;if(e.getKeyCode()==KeyEvent.KEYCODE_ENTER)hidEnters++;status("card","HID 입력 이벤트 "+hidEvents+"개 · 내용 저장 안 함");return true;}return super.dispatchKeyEvent(e);}
    private void sectionSensors(){LinearLayout box=section("센서 / 화면","sensors");LinearLayout r=row(box);button(r,"센서 목록",()->{StringBuilder s=new StringBuilder();for(Sensor sensor:sensors.getSensorList(Sensor.TYPE_ALL))s.append(sensor.getName()).append(" · ").append(sensor.getStringType()).append('\n');status("sensors",s.length()>0?s.toString():"노출된 센서 없음");});button(r,"조도 측정",()->{Sensor light=sensors.getDefaultSensor(Sensor.TYPE_LIGHT);if(light==null){status("sensors","조도 센서 없음");return;}final int current=++lightGeneration;sensors.unregisterListener(this);lightListening=sensors.registerListener(this,light,SensorManager.SENSOR_DELAY_NORMAL);status("sensors",lightListening?"10초간 조도 측정 중":"조도 센서 등록 실패");ui.postDelayed(()->{if(current==lightGeneration){lightListening=false;sensors.unregisterListener(this);}},10000);});button(r,"화면 / 터치",()->screenTest());}
    @Override public void onSensorChanged(SensorEvent e){if(lightListening&&e.sensor.getType()==Sensor.TYPE_LIGHT)status("sensors",String.format(Locale.ROOT,"조도 %.1f lx",e.values[0]));}
    @Override public void onAccuracyChanged(Sensor s,int a){}
    private void screenTest(){final int[] colors={Color.RED,Color.GREEN,Color.BLUE,Color.WHITE,Color.BLACK};final int[] step={0},touches={0};TextView area=new TextView(this);area.setGravity(Gravity.CENTER);area.setTypeface(bold);area.setTextColor(Color.WHITE);area.setTextSize(22);area.setText("화면을 눌러 다음 색상\n다섯 색상 확인 후 종료");area.setBackgroundColor(colors[0]);Dialog d=new Dialog(this,android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);d.setContentView(area);area.setOnTouchListener((v,e)->{touches[0]=Math.max(touches[0],e.getPointerCount());if(e.getActionMasked()==MotionEvent.ACTION_UP){step[0]++;if(step[0]>=colors.length){status("touch","색상 전환 5개 / 최대 동시 터치 "+touches[0]+"점 · 화면 품질은 육안 확인 필요");d.dismiss();}else{area.setBackgroundColor(colors[step[0]]);area.setTextColor(step[0]==3?Color.BLACK:Color.WHITE);}}return true;});d.show();}
    private void sectionLed(){LinearLayout box=section("RGB 표시등","led");text(box,"기존 RGB 효과를 끄고 실행하세요. 각 색상을 낮은 밝기로 표시한 뒤 원래 값을 복원합니다. IR LED는 제어하지 않습니다.",13,false);LinearLayout r=row(box);button(r,"IC",()->pulseLed("_ic"));button(r,"MSR",()->pulseLed("_msr"));button(r,"얼굴 표시등",()->pulseLed(""));}
    private void pulseLed(String suffix) {
        if (ledThread != null && ledThread.isAlive()) {
            status("led", "기존 테스트 종료 후 실행하세요");
            return;
        }
        status("led", "표시등 접근 확인 중");
        ledThread = new Thread(() -> {
            Channel[] channels = Channel.values();
            int start = suffix.equals("_ic") ? 3 : suffix.equals("_msr") ? 6 : 0;
            RgbLed[] leds = new RgbLed[3];
            int[] old = new int[3], max = new int[3];
            boolean touched = false;
            String result;
            try {
                for (int i = 0; i < leds.length; i++) {
                    leds[i] = new RgbLed(java.nio.file.Paths.get("/sys/class/leds"), channels[start + i], true);
                    leds[i].prepare();
                    old[i] = leds[i].get();
                    max[i] = leds[i].maximum();
                    if (max[i] < 1 || old[i] < 0 || old[i] > max[i]) throw new IOException("밝기 범위 확인 실패");
                }
                for (int color = 0; color < leds.length; color++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    touched = true;
                    for (int i = 0; i < leds.length; i++) leds[i].set(i == color ? Math.max(1, max[i] / 10) : 0);
                    Thread.sleep(450);
                }
                result = "RGB 쓰기 완료 · 실제 점등은 육안 확인 필요";
            } catch (InterruptedException e) {
                result = "표시등 테스트 중지";
            } catch (Exception e) {
                result = "표시등 접근 실패 · " + e.getMessage();
            }
            if (touched) {
                boolean restored = true;
                for (int i = 0; i < leds.length; i++) {
                    try {
                        leds[i].set(old[i]);
                    } catch (IOException e) {
                        restored = false;
                    }
                }
                if (!restored) result += " / 원래 밝기 복원 실패";
            }
            final String message = result;
            ui.post(() -> status("led", message));
        }, "DiagnosticLED");
        ledThread.start();
    }
    private void inspectPayment(){JSONObject result=CardPaymentDiagnostics.inspect(this);try{results.put("payment_details",result);}catch(JSONException ignored){}StringBuilder summary=new StringBuilder();String[] keys={"payment_app","payment_service","serial_device","payment_module","ic","msr","payment_nfc"};String[] labels={"결제 앱","결제 서비스","리더 연결","모듈 응답","IC 삽입","MSR 슬라이드","결제 NFC"};for(int i=0;i<keys.length;i++)summary.append(labels[i]).append(": ").append(result.optString(keys[i])).append("\n");status("payment",summary.toString());}
    private String report(){JSONObject doc=new JSONObject();try{doc.put("app","Connect Diagnostics 0.1.0");doc.put("model",Build.MODEL);doc.put("android",Build.VERSION.RELEASE);doc.put("generated_at_ms",System.currentTimeMillis());doc.put("results",new JSONObject(results.toString()));doc.put("payment_transaction_executed",false);doc.put("card_contents_recorded",false);}catch(JSONException ignored){}return doc.toString();}
    private void copyReport(){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("커넥트 진단",report()));Toast.makeText(this,"복사됨",Toast.LENGTH_SHORT).show();}
    private void saveReport(){pendingExport=report();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"connect-diagnostics-"+System.currentTimeMillis()+".json");startActivityForResult(i,20);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req!=20)return;String snapshot=pendingExport;pendingExport=null;if(result!=RESULT_OK||data==null||data.getData()==null||snapshot==null)return;try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){if(out==null)throw new IOException();out.write(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8));Toast.makeText(this,"저장됨",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"저장 실패",Toast.LENGTH_LONG).show();}}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("results",results.toString());out.putString("export",pendingExport);super.onSaveInstanceState(out);}
    private void stopTests(){stopIcQuery(false);hidGeneration++;lightGeneration++;hidListening=false;lightListening=false;if(audio!=null)audio.stop();if(micLevel!=null)micLevel.setProgress(0);if(camera!=null)camera.stop();if(nfcListening)stopNfc();if(sensors!=null)sensors.unregisterListener(this);if(ledThread!=null)ledThread.interrupt();}
    @Override protected void onResume(){super.onResume();visible=true;if(pendingResume!=null){Runnable next=pendingResume;pendingResume=null;next.run();}}
    @Override protected void onPause(){visible=false;stopTests();super.onPause();}
    @Override protected void onDestroy(){stopTests();ui.removeCallbacksAndMessages(null);super.onDestroy();}
}
