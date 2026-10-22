package ru.app4dad.windows;

import ru.app4dad.core.SyncEngine;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class Controller implements AutoCloseable {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancel=new AtomicBoolean();
    private SyncEngine.Plan plan;
    private String source="S:\\Михеев",target="E:\\Михеев",status="Выберите период и сравните папки",phase="idle",log="";
    private boolean busy;
    private String dateFrom="",dateTo="";
    public synchronized boolean busy() { return busy; }
    public synchronized SyncEngine.Plan plan() { return plan; }
    public synchronized String log() { return log; }
    public synchronized void invalidatePlan() { if(!busy) { plan=null; phase="idle"; status="Параметры изменены. Сравните папки заново"; } }
    public synchronized void configure(String s,String t) {
        if(busy) throw new IllegalStateException("Дождитесь завершения операции");
        if(!source.equals(s)||!target.equals(t)) { source=s; target=t; plan=null; status="Папки изменены. Сравните заново"; }
    }
    public synchronized void scan(LocalDate from,LocalDate to) {
        if(busy) throw new IllegalStateException("Операция уже выполняется");
        if(from.isAfter(to)) throw new IllegalArgumentException("Начальная дата позже конечной");
        busy=true; cancel.set(false); plan=null; phase="scanning"; status="Сравнение папок…"; log="";
        dateFrom=from.toString(); dateTo=to.toString();
        final String s=source,t=target;
        worker.submit(()->{
            try {
                SyncEngine.Plan result=SyncEngine.scan(Paths.get(s),Paths.get(t),from,to,ZoneId.systemDefault(),cancel::get,this::progress);
                synchronized(this) { plan=result; phase="ready"; status="К копированию: "+result.count()+"; совпадают: "+result.identical+"; вне периода: "+result.outside; }
            } catch(CancellationException e) { finishError("Сравнение остановлено"); }
            catch(Exception e) { finishError(message(e)); }
            finally { synchronized(this) { busy=false; } }
        });
    }
    public synchronized void copy(String planId,Set<String> selected) {
        if(busy) throw new IllegalStateException("Операция уже выполняется");
        if(plan==null||!plan.id.equals(planId)) throw new IllegalStateException("Список устарел. Сравните папки заново");
        SyncEngine.Plan approved=plan;
        Set<String> chosen=new HashSet<>(selected);
        if(approved.items.stream().noneMatch(i->i.copyable()&&chosen.contains(i.path))) throw new IllegalArgumentException("Не выбраны файлы");
        plan=null; busy=true; cancel.set(false); phase="copying"; status="Копирование…";
        worker.submit(()->{
            try {
                SyncEngine.Report r=SyncEngine.copy(approved,chosen,cancel::get,this::progress);
                synchronized(this) {
                    phase="done"; status=(r.cancelled?"Остановлено. ":"Готово. ")+"Скопировано: "+r.copied+"; ошибок: "+r.failed;
                    log=String.join(System.lineSeparator(),r.messages); if(!log.isEmpty()) log+=System.lineSeparator(); log+=status;
                }
            } catch(Exception e) { finishError(message(e)); }
            finally { synchronized(this) { busy=false; } }
        });
    }
    public synchronized void copyAll(String planId) {
        if(plan==null) throw new IllegalStateException("Сравните папки заново");
        Set<String> selected=new HashSet<>(); for(SyncEngine.Item i:plan.items) if(i.copyable()) selected.add(i.path);
        copy(planId,selected);
    }
    public void cancel() { cancel.set(true); }
    private synchronized void progress(String text) { status=text; }
    private synchronized void finishError(String text) { phase="error"; status=text; log=text; plan=null; }
    static String message(Exception e) { return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); }
    public synchronized Properties state(int offset) {
        Properties p=new Properties(); p.setProperty("ok","true"); p.setProperty("source",source); p.setProperty("target",target);
        p.setProperty("status",status); p.setProperty("phase",phase); p.setProperty("busy",Boolean.toString(busy));
        p.setProperty("from",dateFrom); p.setProperty("to",dateTo);
        p.setProperty("log",log.length()>16000?log.substring(log.length()-16000):log);
        p.setProperty("plan",plan==null?"":plan.id);
        p.setProperty("count",Integer.toString(plan==null?0:plan.count())); p.setProperty("bytes",Long.toString(plan==null?0:plan.bytes()));
        p.setProperty("total",Integer.toString(plan==null?0:plan.items.size()));
        int start=Math.max(0,offset),n=plan==null?0:Math.max(0,Math.min(100,plan.items.size()-start));
        p.setProperty("rows",Integer.toString(n)); p.setProperty("offset",Integer.toString(start));
        for(int r=0;r<n;r++) {
            SyncEngine.Item i=plan.items.get(start+r);
            p.setProperty("row."+r,SyncEngine.label(i.kind)+" · "+i.path+"\n"+i.message+" · "+i.size+" байт");
        }
        return p;
    }
    @Override public void close() { cancel(); worker.shutdownNow(); }
}
