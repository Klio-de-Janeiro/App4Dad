package ru.app4dad.windows;

import ru.app4dad.shared.SecureWire;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in LAN listener. A fresh 256-bit code is generated for every session. */
public final class RemoteServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService pool=Executors.newFixedThreadPool(2);
    private final byte[] key;
    private final Controller controller;
    private final Map<String,Long> seen=new HashMap<>();
    private long failureWindow=0;
    private int failures=0;
    public final String code=SecureWire.newCode();
    public RemoteServer(Controller controller,int port) throws IOException {
        this.controller=controller; key=SecureWire.key(code);
        server=HttpServer.create(new InetSocketAddress("0.0.0.0",port),8);
        server.createContext("/rpc",this::handle); server.setExecutor(pool); server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    private synchronized boolean limited() {
        long now=System.currentTimeMillis();
        if(now-failureWindow>60000) { failures=0; failureWindow=now; }
        return failures>=30;
    }
    private synchronized void failed() { failures++; }
    private synchronized void replayCheck(Properties p) throws Exception {
        long now=System.currentTimeMillis(),time=Long.parseLong(p.getProperty("time","0"));
        String id=p.getProperty("id","");
        seen.entrySet().removeIf(e->now-e.getValue()>300000);
        if(Math.abs(now-time)>120000||id.length()!=36||seen.containsKey(id)||seen.size()>2000) throw new SecurityException("Запрос устарел или повторён. Проверьте время на телефоне");
        seen.put(id,now);
    }
    private void handle(HttpExchange x) throws IOException {
        try {
            if(!"POST".equals(x.getRequestMethod())||!"/rpc".equals(x.getRequestURI().getPath())||limited()) { x.sendResponseHeaders(403,-1); return; }
            byte[] body=x.getRequestBody().readNBytes(65537);
            if(body.length>65536) { x.sendResponseHeaders(413,-1); return; }
            Properties request;
            try { request=SecureWire.decrypt(body,key); }
            catch(Exception e) { failed(); x.sendResponseHeaders(401,-1); return; }
            Properties response;
            try {
                replayCheck(request);
                switch(request.getProperty("action","")) {
                    case "state": break;
                    case "scan": controller.scan(LocalDate.parse(request.getProperty("from")),LocalDate.parse(request.getProperty("to"))); break;
                    case "copy": controller.copyAll(request.getProperty("plan","")); break;
                    case "cancel": controller.cancel(); break;
                    default: throw new IllegalArgumentException("Неизвестная команда");
                }
                response=controller.state(Integer.parseInt(request.getProperty("offset","0")));
            } catch(Exception e) { response=new Properties(); response.setProperty("ok","false"); response.setProperty("error",Controller.message(e)); }
            response.setProperty("id",request.getProperty("id",""));
            try {
                byte[] result=SecureWire.encrypt(response,key);
                x.getResponseHeaders().set("Content-Type","application/octet-stream"); x.getResponseHeaders().set("Cache-Control","no-store");
                x.sendResponseHeaders(200,result.length); x.getResponseBody().write(result);
            } catch(Exception e) { throw new IOException(e); }
        } finally { x.close(); }
    }
    public static String addresses(int port) throws SocketException {
        List<String> result=new ArrayList<>();
        Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
        while(interfaces.hasMoreElements()) {
            NetworkInterface ni=interfaces.nextElement(); if(!ni.isUp()||ni.isLoopback()) continue;
            Enumeration<InetAddress> addresses=ni.getInetAddresses();
            while(addresses.hasMoreElements()) { InetAddress a=addresses.nextElement(); if(a instanceof Inet4Address) result.add(a.getHostAddress()+":"+port); }
        }
        return result.isEmpty()?"Нет подключения к локальной сети":String.join("   |   ",result);
    }
    @Override public void close() { server.stop(0); pool.shutdownNow(); Arrays.fill(key,(byte)0); }
}
