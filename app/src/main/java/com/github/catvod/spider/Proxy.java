package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Map;
import com.github.catvod.utils.Path;

public class Proxy extends Spider {

    private static int port = -1;

    public static Object[] proxy(Map<String, String> params) throws Exception {
        //return XiaoyaProxyHandler.proxy(params);
        switch (params.get("do")) {
            case "ck":
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes("UTF-8"))};
            case "dbg":
                Logger.dbg = true;
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes("UTF-8"))};
            case "fs":
                return readFile(params);
            default:
                return null;
        }
    }

    private static Object[] readFile(Map<String, String> params) throws Exception {
        String filePath = params.get("file");
        if (!filePath.isEmpty()) {
            File file = new File(filePath);
            return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream(Path.read(file).getBytes("UTF-8"))};
        } else {
            return null;
        }
    }

    static void adjustPort() {
        if (Proxy.port > 0) return;
        int port = 9978;
        while (port < 10000) {
            String resp = OkHttp.string("http://127.0.0.1:" + port + "/proxy?do=ck", null);
            if (resp.equals("ok")) {
                SpiderDebug.log("Found local server port " + port);
                Proxy.port = port;
                break;
            }
            port++;
        }
    }

    public static int getPort() {
        adjustPort();
        return port;
    }

    public static String getUrl() {
        adjustPort();
        return "http://127.0.0.1:" + port + "/proxy";
    }
}
