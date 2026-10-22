package ru.app4dad.core;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/** One-way, manually approved copy. Planning never writes to either tree. */
public final class SyncEngine {
    public enum Kind { NEW, CHANGED, CONFLICT, ERROR }
    public static final class Item {
        public final String path, message, sourceHash, targetHash;
        public final long size, modified, targetSize, targetModified;
        public final Kind kind;
        public Item(String p, Kind k, String msg, long s, long m, String sh, long ts, long tm, String th) {
            path=p; kind=k; message=msg; size=s; modified=m; sourceHash=sh;
            targetSize=ts; targetModified=tm; targetHash=th;
        }
        public boolean copyable() { return kind==Kind.NEW || kind==Kind.CHANGED; }
    }
    public static final class Plan {
        public final String id=UUID.randomUUID().toString();
        public final Path source, target;
        public final List<Item> items;
        public final int scanned, identical, outside;
        Plan(Path s, Path t, List<Item> rows, int[] counts) {
            source=s; target=t; items=Collections.unmodifiableList(rows);
            scanned=counts[0]; identical=counts[1]; outside=counts[2];
        }
        public int count() { return (int)items.stream().filter(Item::copyable).count(); }
        public long bytes() { return items.stream().filter(Item::copyable).mapToLong(i->i.size).sum(); }
    }
    public static final class Report {
        public int copied, failed;
        public boolean cancelled;
        public final List<String> messages=new ArrayList<>();
    }
    private static void check(BooleanSupplier stop) {
        if(stop.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Остановлено");
    }
    public static Path[] validate(Path source, Path target) throws IOException {
        Path s=source.toAbsolutePath().normalize();
        Path t=target.toAbsolutePath().normalize();
        if(!Files.isDirectory(s)) throw new IOException("Папка источника недоступна: "+s);
        if(Files.isSymbolicLink(s) || Files.isSymbolicLink(t)) throw new IOException("Выберите обычные папки без ссылок");
        s=s.toRealPath();
        Path existing=t;
        while(existing!=null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing=existing.getParent();
        if(existing==null || !Files.isDirectory(existing)) throw new IOException("Диск назначения недоступен: "+t);
        t=existing.toRealPath().resolve(existing.relativize(t)).normalize();
        if(s.startsWith(t)||t.startsWith(s)||(Files.exists(t)&&Files.isSameFile(s,t))) throw new IOException("Папки должны быть разными и не находиться одна внутри другой");
        if(Files.exists(t) && !Files.isDirectory(t)) throw new IOException("Назначение должно быть папкой");
        return new Path[]{s,t};
    }
    // Resolving every existing component prevents junctions/symlinks from escaping the chosen root.
    private static Path safe(Path root, String relative) throws IOException {
        if(Files.exists(root,LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(root)||!root.toRealPath().equals(root.toAbsolutePath().normalize())))
            throw new IOException("Корневая папка была перенаправлена");
        Path p=root.resolve(relative).normalize();
        if(!p.startsWith(root)||p.equals(root)) throw new IOException("Недопустимый путь");
        Path part=root;
        for(Path name:root.relativize(p)) {
            part=part.resolve(name);
            if(Files.exists(part,LinkOption.NOFOLLOW_LINKS)) {
                if(Files.isSymbolicLink(part) || !part.toRealPath().equals(part.toAbsolutePath().normalize()))
                    throw new IOException("Ссылка или перенаправление: "+part);
            }
        }
        return p;
    }
    private static String hash(Path path, BooleanSupplier stop) throws IOException {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[128*1024];
            try(InputStream in=Files.newInputStream(path)) {
                int n; while((n=in.read(buffer))!=-1) { check(stop); md.update(buffer,0,n); }
            }
            StringBuilder out=new StringBuilder();
            for(byte b:md.digest()) out.append(String.format("%02x",b&255));
            return out.toString();
        } catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static Plan scan(Path source, Path target, LocalDate from, LocalDate through,
                            ZoneId zone, BooleanSupplier stop, Consumer<String> progress) throws IOException {
        if(from.isAfter(through)) throw new IOException("Начальная дата позже конечной");
        Path[] roots=validate(source,target); Path s=roots[0],t=roots[1];
        long begin=from.atStartOfDay(zone).toInstant().toEpochMilli();
        long end=through.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        List<Item> rows=new ArrayList<>(); int[] counts={0,0,0};
        Files.walkFileTree(s,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs) throws IOException {
                check(stop);
                if(!dir.equals(s)) {
                    String rel=s.relativize(dir).toString();
                    try { safe(s,rel); }
                    catch(IOException e) { rows.add(error(rel,e)); return FileVisitResult.SKIP_SUBTREE; }
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) {
                check(stop); String rel=s.relativize(file).toString(); counts[0]++;
                progress.accept("Проверяется: "+rel);
                try {
                    if(!attrs.isRegularFile()||attrs.isSymbolicLink()) throw new IOException("Ссылки и специальные файлы пропущены");
                    long m=attrs.lastModifiedTime().toMillis(),size=attrs.size();
                    if(m<begin||m>=end) { counts[2]++; return FileVisitResult.CONTINUE; }
                    Path src=safe(s,rel),dst=safe(t,rel);
                    String sh=hash(src,stop);
                    BasicFileAttributes after=Files.readAttributes(src,BasicFileAttributes.class);
                    if(size!=after.size()||m!=after.lastModifiedTime().toMillis()) throw new IOException("Файл меняется во время сравнения");
                    if(!Files.exists(dst,LinkOption.NOFOLLOW_LINKS)) rows.add(new Item(rel,Kind.NEW,"Нет на флешке",size,m,sh,-1,-1,""));
                    else if(!Files.isRegularFile(dst,LinkOption.NOFOLLOW_LINKS)) rows.add(new Item(rel,Kind.CONFLICT,"На месте файла находится папка",size,m,sh,-1,-1,""));
                    else {
                        BasicFileAttributes da=Files.readAttributes(dst,BasicFileAttributes.class);
                        String dh=hash(dst,stop);
                        BasicFileAttributes da2=Files.readAttributes(dst,BasicFileAttributes.class);
                        if(da.size()!=da2.size()||!da.lastModifiedTime().equals(da2.lastModifiedTime())) throw new IOException("Копия меняется во время сравнения");
                        if(sh.equals(dh)) counts[1]++;
                        else rows.add(new Item(rel,da.lastModifiedTime().toMillis()>m+2000?Kind.CONFLICT:Kind.CHANGED,
                            da.lastModifiedTime().toMillis()>m+2000?"На флешке более новая версия":"Содержимое отличается",
                            size,m,sh,da.size(),da.lastModifiedTime().toMillis(),dh));
                    }
                } catch(IOException|SecurityException e) { rows.add(error(rel,e)); }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,IOException e) {
                check(stop); rows.add(error(s.relativize(file).toString(),e)); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException e) {
                check(stop); if(e!=null) rows.add(error(s.relativize(dir).toString(),e)); return FileVisitResult.CONTINUE;
            }
        });
        rows.sort(Comparator.comparing(i->i.path.toLowerCase(Locale.ROOT)));
        return new Plan(s,t,rows,counts);
    }
    private static Item error(String p,Exception e) { return new Item(p,Kind.ERROR,e.getMessage(),0,0,"",-1,-1,""); }
    public static Report copy(Plan plan, Set<String> selected, BooleanSupplier stop, Consumer<String> progress) throws IOException {
        Path[] roots=validate(plan.source,plan.target);
        if(!roots[0].equals(plan.source)||!roots[1].equals(plan.target)) throw new IOException("Папки изменились. Сравните заново");
        Report report=new Report();
        for(Item item:plan.items) {
            if(!item.copyable()||!selected.contains(item.path)) continue;
            Path temp=null;
            try {
                check(stop); Path src=safe(plan.source,item.path),dst=safe(plan.target,item.path);
                verifySource(src,item,stop); verifyTarget(dst,item,stop);
                Files.createDirectories(dst.getParent());
                dst=safe(plan.target,item.path);
                temp=Files.createTempFile(dst.getParent(),".app4dad-", ".part");
                progress.accept("Копируется: "+item.path);
                try(InputStream in=Files.newInputStream(src); OutputStream out=Files.newOutputStream(temp)) {
                    byte[] buf=new byte[128*1024]; int n;
                    while((n=in.read(buf))!=-1) { check(stop); out.write(buf,0,n); }
                }
                if(!hash(temp,stop).equals(item.sourceHash)) throw new IOException("Проверка записанных данных не пройдена");
                verifySource(src,item,stop); verifyTarget(dst,item,stop); check(stop);
                Files.setLastModifiedTime(temp,FileTime.fromMillis(item.modified));
                // No non-atomic fallback: an unsupported filesystem leaves the old file intact.
                if(item.kind==Kind.NEW) {
                    // A hard-link publish would not work on FAT/exFAT; no replace for new files.
                    Files.move(temp,dst);
                } else Files.move(temp,dst,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                temp=null; report.copied++; report.messages.add("Готово: "+item.path);
            } catch(CancellationException e) { report.cancelled=true; break; }
            catch(IOException|SecurityException e) { report.failed++; report.messages.add("Ошибка: "+item.path+" — "+e.getMessage()); }
            finally { if(temp!=null) try { Files.deleteIfExists(temp); } catch(IOException e) { report.messages.add("Не удалось убрать временный файл: "+temp); } }
        }
        return report;
    }
    private static void verifySource(Path p,Item i,BooleanSupplier stop) throws IOException {
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)!=i.size||Files.getLastModifiedTime(p).toMillis()!=i.modified||!hash(p,stop).equals(i.sourceHash))
            throw new IOException("Источник изменился. Сравните заново");
    }
    private static void verifyTarget(Path p,Item i,BooleanSupplier stop) throws IOException {
        if(i.kind==Kind.NEW) {
            if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)) throw new IOException("На флешке появился файл. Сравните заново");
        } else if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)!=i.targetSize||Files.getLastModifiedTime(p).toMillis()!=i.targetModified||!hash(p,stop).equals(i.targetHash))
            throw new IOException("Копия на флешке изменилась. Сравните заново");
    }
    public static String label(Kind kind) {
        switch(kind) { case NEW:return "Новый"; case CHANGED:return "Изменён"; case CONFLICT:return "Конфликт"; default:return "Ошибка"; }
    }
}
