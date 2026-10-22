package ru.app4dad.tests;

import ru.app4dad.core.*;
import ru.app4dad.shared.*;
import ru.app4dad.windows.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AllTests {
    static int checks;
    static final ZoneId ZONE=ZoneId.of("Europe/Moscow");
    static final LocalDate DAY=LocalDate.of(2026,10,22);
    static final long TIME=DAY.atTime(12,0).atZone(ZONE).toInstant().toEpochMilli();
    static void ok(boolean condition,String name) { checks++;if(!condition)throw new AssertionError(name);System.out.println("PASS "+name); }
    interface Task { void run() throws Exception; }
    static void rejects(Task task,String name) throws Exception { try { task.run(); } catch(Exception e) { ok(true,name);return; }throw new AssertionError(name); }
    static Path file(Path root,String name,String content,long time) throws IOException { Path p=root.resolve(name);Files.createDirectories(p.getParent());Files.write(p,content.getBytes(StandardCharsets.UTF_8));Files.setLastModifiedTime(p,FileTime.fromMillis(time));return p; }
    static SyncEngine.Plan scan(Path s,Path d) throws Exception { return SyncEngine.scan(s,d,DAY.minusDays(1),DAY,ZONE,()->false,m->{}); }
    static SyncEngine.Item find(SyncEngine.Plan p,String name) { return p.items.stream().filter(i->i.path.equals(name.replace('/',File.separatorChar))).findFirst().orElseThrow(()->new AssertionError("Missing "+name)); }
    static Set<String> all(SyncEngine.Plan p) { Set<String> paths=new HashSet<>();p.items.forEach(i->paths.add(i.path));return paths; }
    static SyncEngine.Report copy(SyncEngine.Plan p) throws Exception { return SyncEngine.copy(p,all(p),()->false,m->{}); }
    static Properties rpc(int port,byte[] key,Properties p) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/rpc").openConnection();c.setConnectTimeout(3000);c.setReadTimeout(10000);c.setRequestMethod("POST");c.setDoOutput(true);
        try { try(OutputStream out=c.getOutputStream()) {out.write(SecureWire.encrypt(p,key));} if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode()); Properties response=SecureWire.decrypt(c.getInputStream().readAllBytes(),key);ok(p.getProperty("id").equals(response.getProperty("id")),"response bound to request");return response; }finally { c.disconnect(); }
    }
    static void waitIdle(Controller c) throws Exception { long until=System.currentTimeMillis()+10000;while(c.busy()&&System.currentTimeMillis()<until)Thread.sleep(10);ok(!c.busy(),"operation finishes"); }
    public static void main(String[] args) throws Exception {
        Path temp=Files.createTempDirectory(Paths.get("build"),"functional-tests-").toAbsolutePath();
        try {
            Path s=Files.createDirectories(temp.resolve("source")),d=temp.resolve("target");
            file(s,"подпапка/новый.txt","новый",TIME);file(s,"old.txt","old",TIME-10*86400000L);
            long begin=DAY.minusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
            long end=DAY.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
            file(s,"begin.txt","",begin);file(s,"last.txt","last",end-1);file(s,"tomorrow.txt","future",end);
            SyncEngine.Plan first=scan(s,d);ok(!Files.exists(d),"scan never creates destination");ok(first.count()==3,"inclusive date bounds and old missing file excluded");ok(first.outside==2,"outside range tracked");
            SyncEngine.Report result=copy(first);ok(result.copied==3&&result.failed==0,"nested and empty files copied");ok(Files.getLastModifiedTime(d.resolve("подпапка/новый.txt")).toMillis()==TIME,"modification time preserved");ok(scan(s,d).count()==0,"second scan is idempotent");
            file(d,"destination-only.txt","keep",TIME);copy(scan(s,d));ok(Files.exists(d.resolve("destination-only.txt")),"destination-only file retained");
            file(s,"same-metadata.txt","AAAA",TIME);file(d,"same-metadata.txt","BBBB",TIME);SyncEngine.Plan changed=scan(s,d);ok(find(changed,"same-metadata.txt").kind==SyncEngine.Kind.CHANGED,"hash detects same-size same-time changes");ok(copy(changed).copied==1,"existing file replaced atomically");ok(Files.readString(d.resolve("same-metadata.txt")).equals("AAAA"),"replacement content correct");
            file(s,"conflict.txt","source",TIME);file(d,"conflict.txt","newer",TIME+60000);ok(find(scan(s,d),"conflict.txt").kind==SyncEngine.Kind.CONFLICT,"newer target protected");copy(scan(s,d));ok(Files.readString(d.resolve("conflict.txt")).equals("newer"),"conflict not copied");
            file(s,"fat.txt","source",TIME);file(d,"fat.txt","target",TIME+1000);ok(find(scan(s,d),"fat.txt").kind==SyncEngine.Kind.CHANGED,"FAT timestamp tolerance");
            file(s,"equal.txt","equal",TIME);file(d,"equal.txt","equal",TIME+60000);ok(scan(s,d).items.stream().noneMatch(i->i.path.equals("equal.txt")),"identical content with newer date skipped");
            file(s,"folder-conflict","file",TIME);Files.createDirectory(d.resolve("folder-conflict"));ok(find(scan(s,d),"folder-conflict").kind==SyncEngine.Kind.CONFLICT,"file versus folder conflict");
            file(s,"raced-source.txt","AAAA",TIME);SyncEngine.Plan staleSource=scan(s,d);file(s,"raced-source.txt","BBBB",TIME);SyncEngine.Report stale=SyncEngine.copy(staleSource,Set.of("raced-source.txt"),()->false,m->{});ok(stale.failed==1&&!Files.exists(d.resolve("raced-source.txt")),"source change after preview rejected");
            file(s,"raced-target.txt","AAAA",TIME);file(d,"raced-target.txt","BBBB",TIME);SyncEngine.Plan staleTarget=scan(s,d);file(d,"raced-target.txt","CCCC",TIME);ok(SyncEngine.copy(staleTarget,Set.of("raced-target.txt"),()->false,m->{}).failed==1&&Files.readString(d.resolve("raced-target.txt")).equals("CCCC"),"target change after preview rejected");
            file(s,"appeared.txt","AAAA",TIME);SyncEngine.Plan appeared=scan(s,d);file(d,"appeared.txt","other",TIME);ok(SyncEngine.copy(appeared,Set.of("appeared.txt"),()->false,m->{}).failed==1,"new target appearing after scan protected");
            file(s,"cancel.txt","x".repeat(300000),TIME);SyncEngine.Plan cancellation=scan(s,d);AtomicBoolean stop=new AtomicBoolean();SyncEngine.Report cancelled=SyncEngine.copy(cancellation,Set.of("cancel.txt"),stop::get,m->stop.set(true));ok(cancelled.cancelled&&!Files.exists(d.resolve("cancel.txt")),"cancel mid-file leaves no partial destination");try(var files=Files.list(d)){ok(files.noneMatch(p->p.getFileName().toString().endsWith(".part")),"temporary files cleaned after cancel");}
            rejects(()->scan(s,s),"same roots rejected");rejects(()->scan(s,s.resolve("nested")),"nested destination rejected");rejects(()->scan(s,temp),"ancestor destination rejected");rejects(()->scan(temp.resolve("absent"),d),"missing source rejected");rejects(()->SyncEngine.scan(s,d,DAY,DAY.minusDays(1),ZONE,()->false,m->{}),"reversed dates rejected");rejects(()->SyncEngine.scan(s,d,DAY,DAY,ZONE,()->true,m->{}),"scan cancellation honored");
            if(System.getProperty("os.name").startsWith("Windows")) {
                Path outside=Files.createDirectories(temp.resolve("outside")),junction=s.resolve("junction");file(outside,"hidden.txt","outside",TIME);
                Process link=new ProcessBuilder("cmd.exe","/c","mklink","/J",junction.toString(),outside.toString()).redirectErrorStream(true).start();String output=new String(link.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                if(link.waitFor()!=0)throw new IOException("Cannot set up junction test: "+output);
                try { SyncEngine.Plan protectedPlan=scan(s,d);ok(protectedPlan.items.stream().noneMatch(i->i.path.contains("hidden.txt")),"source junction never traversed");ok(find(protectedPlan,"junction").kind==SyncEngine.Kind.ERROR,"source junction reported"); }
                finally {Files.delete(junction);}
                Path targetJunction=d.resolve("redirect");file(s,"redirect/escape.txt","stay inside",TIME);
                Process destLink=new ProcessBuilder("cmd.exe","/c","mklink","/J",targetJunction.toString(),outside.toString()).redirectErrorStream(true).start();destLink.getInputStream().readAllBytes();if(destLink.waitFor()!=0)throw new IOException("Cannot create target junction");
                try { SyncEngine.Plan protectedPlan=scan(s,d);ok(find(protectedPlan,"redirect/escape.txt").kind==SyncEngine.Kind.ERROR,"destination junction rejected");copy(protectedPlan);ok(!Files.exists(outside.resolve("escape.txt")),"no write outside destination"); }
                finally {Files.delete(targetJunction);}
            }
            byte[] key=SecureWire.key(SecureWire.newCode());Properties plain=SecureWire.request("state");plain.setProperty("path","S:\\Михеев\\тест.txt");byte[] encrypted=SecureWire.encrypt(plain,key);ok(SecureWire.decrypt(encrypted,key).equals(plain),"AES-GCM unicode round trip");ok(!Arrays.equals(encrypted,SecureWire.encrypt(plain,key)),"unique nonce per message");rejects(()->SecureWire.decrypt(encrypted,SecureWire.key(SecureWire.newCode())),"wrong pairing key rejected");encrypted[encrypted.length-1]^=1;rejects(()->SecureWire.decrypt(encrypted,key),"tampered packet rejected");rejects(()->SecureWire.key("bad"),"invalid pairing code rejected");
            try(Controller c=new Controller()) {
                Path netS=Files.createDirectories(temp.resolve("network-source")),netD=temp.resolve("network-target");file(netS,"remote.txt","remote",TIME);c.configure(netS.toString(),netD.toString());
                try(RemoteServer server=new RemoteServer(c,0)) {
                    byte[] session=SecureWire.key(server.code);Properties request=SecureWire.request("state");ok(rpc(server.port(),session,request).getProperty("source").equals(netS.toString()),"authenticated LAN state");ok("false".equals(rpc(server.port(),session,request).getProperty("ok")),"replayed request rejected");
                    Properties expired=SecureWire.request("state");expired.setProperty("time","0");ok("false".equals(rpc(server.port(),session,expired).getProperty("ok")),"expired request rejected");rejects(()->rpc(server.port(),SecureWire.key(SecureWire.newCode()),SecureWire.request("state")),"unauthenticated LAN command rejected");
                    Properties reqScan=SecureWire.request("scan");reqScan.setProperty("from",DAY.toString());reqScan.setProperty("to",DAY.toString());rpc(server.port(),session,reqScan);waitIdle(c);Properties ready=rpc(server.port(),session,SecureWire.request("state"));ok(ready.getProperty("count").equals("1")&&!Files.exists(netD),"remote scan is read-only");
                    Properties bad=SecureWire.request("copy");bad.setProperty("plan","old-plan");ok("false".equals(rpc(server.port(),session,bad).getProperty("ok")),"stale plan ID rejected");
                    Properties approved=SecureWire.request("copy");approved.setProperty("plan",ready.getProperty("plan"));rpc(server.port(),session,approved);waitIdle(c);ok(Files.readString(netD.resolve("remote.txt")).equals("remote"),"remote approved copy reaches PC destination");ok(c.plan()==null,"used plan cannot be reused");
                }
            }
            System.out.println("ALL "+checks+" CHECKS PASSED");
        } finally {
            // Only delete the exact temporary tree created by this test run.
            Path safeRoot=Paths.get("build").toAbsolutePath().normalize();if(!temp.startsWith(safeRoot))throw new IllegalStateException("Unsafe cleanup");
            try(var paths=Files.walk(temp)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p); }
        }
    }
}
