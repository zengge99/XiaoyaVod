package com.github.catvod.spider;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import com.google.gson.Gson;
import com.github.catvod.utils.Path;

public class Logger {
    static boolean dbg = true;

    public static void log(Object message) {
        if (!dbg) {
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
        //String filePath = "/storage/emulated/0/TV/log.txt";
        String filePath = Path.root().getPath();
        if (filePath == null) {
            return;
        }
        filePath = filePath + "/TV/log.txt";
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath, true))) {
            writer.write(loggerMessage);
            writer.newLine();
            writer.newLine();
        } catch (IOException e) {
            System.err.println("Error writing to log file: " + e.getMessage());
        }
    }
}
