package com.github.catvod.spider;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.File;
import java.io.IOException;
import com.google.gson.Gson;
import com.github.catvod.utils.Path;
import com.github.catvod.bean.alist.Drive;
import android.util.Base64;

public class Logger {
    static boolean dbg = false;
    static String logRootPath = Path.root().getPath() + "/TV/";
    private static Drive logDrive = null;

    /** 由 AListSh.init 注入 defaultDrive，用于 remote log。 */
    public static void setDrive(Drive drive) {
        logDrive = drive;
    }

    public static void log(Object message) {
        try {
            File logSwFile = new File(logRootPath + "dbg");
            if (logSwFile.exists()) {
                dbg = true;
            }
            boolean remote = logDrive != null && logDrive.remoteLog();
            if (!dbg && !remote) {
                return;
            }
            String callPrefix = "";
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            for (int i = 0; i < stackTrace.length; i++) {
                StackTraceElement caller = stackTrace[i];
                String className = caller.getClassName();
                String methodName = caller.getMethodName();
                String fullName = String.format("%s.%s", className, methodName);
                if (fullName.equals("com.github.catvod.spider.Logger.log") && i <= stackTrace.length - 2) {
                    caller = stackTrace[i + 1];
                    className = caller.getClassName();
                    methodName = caller.getMethodName();
                    int lineNumber = caller.getLineNumber();
                    callPrefix = String.format("Log (called from %s.%s at line %d): ", className, methodName, lineNumber);
                    break;
                }
            }

            String loggerMessage = "";
            if (String.class.isInstance(message)) {
                loggerMessage = callPrefix + message;
            } else {
                loggerMessage = callPrefix + (new Gson()).toJson(message);
            }

            if (dbg) {
                writeLocal(loggerMessage);
            }
            if (remote) {
                writeRemote(loggerMessage);
            }
        } catch (Exception e) {
        }
    }

    private static void writeLocal(String loggerMessage) {
        try {
            File logRootDir = new File(logRootPath);
            if (!logRootDir.exists()) {
                logRootDir.mkdirs();
            }
            String logFilePath = logRootPath + "log.txt";
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFilePath, true))) {
                writer.write(loggerMessage);
                writer.newLine();
                writer.newLine();
            } catch (IOException e) {
                System.err.println("Error writing to log file: " + e.getMessage());
            }
        } catch (Exception e) {
        }
    }

    /** remote log：通过 defaultDrive.exec 在远端服务器追加 log.txt（base64 避免转义）。 */
    private static void writeRemote(String loggerMessage) {
        try {
            if (logDrive == null) return;
            String b64 = Base64.encodeToString((loggerMessage + "\n").getBytes("UTF-8"), Base64.NO_WRAP);
            logDrive.exec("printf '%s' '" + b64 + "' | base64 -d >> log.txt");
        } catch (Throwable t) {
            System.err.println("Error writing remote log: " + t.getMessage());
        }
    }
}