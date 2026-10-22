package ru.app4dad.remote;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import ru.app4dad.shared.SecureWire;
import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private EditText address,code;
    private Button connect,scan,copy,cancel,fromButton,toButton,previous,next;
    private TextView status,paths,summary,page;
    private LinearLayout rows;
    private ProgressBar progress;
    private LocalDate from=LocalDate.now().minusDays(1),to=LocalDate.now();
    private final DateTimeFormatter dates=DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private Properties state;
    private String connectedAddress;
    private byte[] sessionKey;
    private boolean requestBusy=false,active=false;
    private int offset=0;
    private final Runnable poll=()->request("state");
    private final int teal=Color.rgb(17,121,110),ink=Color.rgb(28,43,57);
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); getWindow().setStatusBarColor(teal); getWindow().setNavigationBarColor(Color.WHITE);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20),dp(20),dp(20),dp(28)); root.setBackgroundColor(Color.rgb(245,247,250)); scroll.addView(root); setContentView(scroll);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            view.setPadding(dp(20)+insets.getSystemWindowInsetLeft(),dp(20)+insets.getSystemWindowInsetTop(),dp(20)+insets.getSystemWindowInsetRight(),dp(28)+insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
        TextView heading=text("Копия под рукой",29,true); root.addView(heading); root.addView(text("Пульт для приложения на Windows",15,false)); gap(root,20);
        LinearLayout connection=card(root); connection.addView(text("1  Подключитесь к компьютеру",19,true));
        connection.addView(text("На ПК нажмите «Подключить Android-пульт». Телефон должен быть в той же локальной сети.",14,false));
        address=edit("Адрес ПК, например 192.168.1.10:48123",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI); address.setSingleLine(true);
        address.setText(getPreferences(0).getString("address","")); connection.addView(address);
        code=edit("Код сопряжения из окна Windows",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); code.setSingleLine(true); code.setSaveEnabled(false); connection.addView(code);
        connect=button("Подключиться",()->{
            try { sessionKey=SecureWire.key(code.getText().toString()); connectedAddress=address.getText().toString().trim(); state=null; offset=0; request("state"); }
            catch(Exception e) { showError(e.getMessage()); }
        }); connection.addView(connect);
        gap(root,16); LinearLayout datesCard=card(root); datesCard.addView(text("2  Выберите даты изменения",19,true));
        datesCard.addView(text("Обе даты включены. Файлы копируются с диска ПК на флешку, подключённую к ПК.",14,false));
        fromButton=button("",()->pickDate(true)); toButton=button("",()->pickDate(false));
        LinearLayout dateLine=new LinearLayout(this); dateLine.addView(fromButton,new LinearLayout.LayoutParams(0,-2,1)); dateLine.addView(toButton,new LinearLayout.LayoutParams(0,-2,1)); datesCard.addView(dateLine);
        datesCard.addView(button("Вчера и сегодня",()->{ from=LocalDate.now().minusDays(1); to=LocalDate.now(); updateDates(); }));
        datesCard.addView(button("Все даты",()->{ from=LocalDate.of(1970,1,1); to=LocalDate.now().plusYears(100); updateDates(); })); updateDates();
        scan=button("Сравнить папки",()->{ if(from.isAfter(to)) { showError("Начальная дата позже конечной"); return; } offset=0; request("scan"); }); datesCard.addView(scan);
        gap(root,16); LinearLayout result=card(root); result.addView(text("3  Проверьте и скопируйте",19,true));
        paths=text("Папки появятся после подключения",14,false); result.addView(paths);
        status=text("Нет подключения",17,true); result.addView(status); progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setVisibility(View.GONE); result.addView(progress);
        summary=text("Сначала сравните папки",15,false); result.addView(summary);
        copy=button("Скопировать файлы",this::confirmCopy); result.addView(copy);
        cancel=button("Остановить операцию",()->request("cancel")); result.addView(cancel);
        result.addView(button("Обновить состояние",()->request("state")));
        rows=new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); result.addView(rows);
        LinearLayout pages=new LinearLayout(this); previous=button("← Назад",()->{offset=Math.max(0,offset-100);request("state");}); next=button("Далее →",()->{offset+=100;request("state");}); pages.addView(previous,new LinearLayout.LayoutParams(0,-2,1)); pages.addView(next,new LinearLayout.LayoutParams(0,-2,1)); result.addView(pages); page=text("",13,false); result.addView(page);
        gap(root,12); root.addView(text("Доступ с телефона работает, пока пульт включён в Windows. При отключении код меняется. Данные команд и списков файлов зашифрованы.",13,false));
        updateButtons();
    }
    private TextView text(String value,int size,boolean bold) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(ink); t.setPadding(0,dp(6),0,dp(6)); if(bold)t.setTypeface(null,Typeface.BOLD); return t;
    }
    private EditText edit(String hint,int input) { EditText e=new EditText(this); e.setHint(hint); e.setTextSize(15); e.setInputType(input); e.setPadding(dp(6),dp(10),dp(6),dp(10)); return e; }
    private Button button(String value,Runnable action) { Button b=new Button(this); b.setText(value); b.setAllCaps(false); b.setTextColor(teal); b.setOnClickListener(v->action.run()); return b; }
    private LinearLayout card(LinearLayout root) {
        LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(16),dp(12),dp(16),dp(16));
        GradientDrawable bg=new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(16)); c.setBackground(bg); root.addView(c); return c;
    }
    private int dp(int x) { return Math.round(x*getResources().getDisplayMetrics().density); }
    private void gap(LinearLayout p,int size) { Space s=new Space(this); p.addView(s,new LinearLayout.LayoutParams(1,dp(size))); }
    private void pickDate(boolean start) { LocalDate value=start?from:to; new DatePickerDialog(this,(view,y,m,d)->{ if(start)from=LocalDate.of(y,m+1,d);else to=LocalDate.of(y,m+1,d);updateDates(); },value.getYear(),value.getMonthValue()-1,value.getDayOfMonth()).show(); }
    private void updateDates() { fromButton.setText("С "+dates.format(from));toButton.setText("По "+dates.format(to)); if(copy!=null)updateButtons(); }
    private void updateButtons() {
        boolean linked=state!=null,busy=linked&&Boolean.parseBoolean(state.getProperty("busy"));
        connect.setEnabled(!requestBusy); scan.setEnabled(linked&&!busy&&!requestBusy); copy.setEnabled(linked&&!busy&&!requestBusy&&from.toString().equals(state.getProperty("from"))&&to.toString().equals(state.getProperty("to"))&&!state.getProperty("plan","").isEmpty()&&Integer.parseInt(state.getProperty("count","0"))>0);
        cancel.setEnabled(linked&&busy&&!requestBusy); previous.setEnabled(linked&&offset>0&&!requestBusy); next.setEnabled(linked&&offset+100<Integer.parseInt(state.getProperty("total","0"))&&!requestBusy);
    }
    private void confirmCopy() {
        if(state==null)return;
        String plan=state.getProperty("plan","");
        String message="Скопировать "+state.getProperty("count")+" файлов ("+size(Long.parseLong(state.getProperty("bytes","0")))+")?\n\n"+state.getProperty("source")+"\n→ "+state.getProperty("target")+"\n\nИзменённые файлы будут заменены. Конфликты пропускаются. Удалений нет.";
        new AlertDialog.Builder(this).setTitle("Подтверждение копирования").setMessage(message).setNegativeButton("Отмена",null).setPositiveButton("Скопировать",(d,w)->{ if(state!=null&&plan.equals(state.getProperty("plan"))) request("copy"); else showError("Список изменился. Сравните папки заново"); }).show();
    }
    private void request(String action) {
        if(requestBusy||sessionKey==null) return;
        requestBusy=true; handler.removeCallbacks(poll); updateButtons();
        Properties request=SecureWire.request(action); request.setProperty("from",from.toString()); request.setProperty("to",to.toString()); request.setProperty("offset",Integer.toString(offset));
        if(state!=null)request.setProperty("plan",state.getProperty("plan",""));
        final byte[] key=sessionKey.clone(); final String host=connectedAddress;
        network.submit(()->{
            try {
                Properties result=exchange(host,key,request);
                if(!"true".equals(result.getProperty("ok"))) throw new IOException(result.getProperty("error","Ошибка на ПК"));
                handler.post(()->{ requestBusy=false;state=result;getPreferences(0).edit().putString("address",host).apply();render(); });
            } catch(Exception e) { handler.post(()->{ requestBusy=false;state=null;status.setText("Соединение прервано. На ПК операция может продолжаться.");updateButtons();showError(e.getMessage()); }); }
            finally { Arrays.fill(key,(byte)0); }
        });
    }
    private static Properties exchange(String host,byte[] key,Properties request) throws Exception {
        if(!host.matches("[A-Za-z0-9.-]+:[0-9]{1,5}")) throw new IOException("Введите адрес ПК в формате 192.168.1.10:48123");
        URL url=new URL("http://"+host+"/rpc");
        InetAddress ip=InetAddress.getByName(url.getHost());
        if(!ip.isSiteLocalAddress()&&!ip.isLoopbackAddress()&&!ip.isLinkLocalAddress()) throw new IOException("Укажите адрес компьютера в локальной сети");
        // Connect to the validated address, avoiding a second DNS lookup.
        HttpURLConnection connection=(HttpURLConnection)new URL("http://"+ip.getHostAddress()+":"+url.getPort()+"/rpc").openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(6000);connection.setReadTimeout(10000);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/octet-stream");
        try {
            byte[] body=SecureWire.encrypt(request,key); connection.setFixedLengthStreamingMode(body.length);
            try(OutputStream out=connection.getOutputStream()) { out.write(body); }
            int response=connection.getResponseCode();
            if(response!=200) throw new IOException(response==401?"Неверный код сопряжения. Откройте настройки пульта на ПК":"ПК отклонил запрос ("+response+")");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=connection.getInputStream()) { byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>2*1024*1024)throw new IOException("Слишком большой ответ ПК");} }
            Properties result=SecureWire.decrypt(out.toByteArray(),key);
            if(!request.getProperty("id").equals(result.getProperty("id"))) throw new IOException("Ответ не соответствует запросу");return result;
        } finally { connection.disconnect(); }
    }
    private void render() {
        if(isFinishing()||isDestroyed())return;
        status.setText(state.getProperty("status")); paths.setText(state.getProperty("source")+"\n↓\n"+state.getProperty("target"));
        boolean busy=Boolean.parseBoolean(state.getProperty("busy"));progress.setVisibility(busy?View.VISIBLE:View.GONE);progress.setIndeterminate(busy);
        summary.setText("К копированию: "+state.getProperty("count")+" файлов · "+size(Long.parseLong(state.getProperty("bytes","0")))+(state.getProperty("plan","").isEmpty()?"":"\nПериод списка: "+state.getProperty("from")+" — "+state.getProperty("to")));
        rows.removeAllViews();int n=Integer.parseInt(state.getProperty("rows","0"));for(int i=0;i<n;i++)rows.addView(text(state.getProperty("row."+i),14,false));
        String log=state.getProperty("log","");if(n==0&&!log.isEmpty())rows.addView(text(log,14,false));
        int total=Integer.parseInt(state.getProperty("total","0"));page.setText(total==0?"":(offset+1)+"–"+(offset+n)+" из "+total);
        updateButtons();if(busy&&active)handler.postDelayed(poll,1200);
    }
    private static String size(long n) { if(n<1024)return n+" Б";if(n<1048576)return String.format(Locale.ROOT,"%.1f КБ",n/1024d);return String.format(Locale.ROOT,"%.1f МБ",n/1048576d); }
    private void showError(String message) { if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("Не удалось выполнить действие").setMessage(message==null?"Проверьте адрес ПК, код и подключение к сети. Разрешите App4Dad входящие подключения в частной сети Windows.":message).setPositiveButton("Понятно",null).show(); }
    @Override protected void onResume() { super.onResume();active=true;if(state!=null)request("state"); }
    @Override protected void onPause() { active=false;handler.removeCallbacks(poll);super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null);network.shutdownNow();if(sessionKey!=null)Arrays.fill(sessionKey,(byte)0);super.onDestroy(); }
}
