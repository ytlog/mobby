package com.libtermux.executor;

import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Device-only JNI probe; never packaged in the application. Same JNI names as the Kotlin owner. */
public final class PipeProcess {
    private static native int[] spawn(String[] args, String[] env, String cwd, boolean withInput);
    private static native int poll(int pid);
    private static native void reap(int pid);
    private static native void signalGroup(int pid, int signal);
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static int waitExit(int pid) throws Exception {
        for (int i=0;i<200;i++) { int exit=poll(pid); if(exit>=0)return exit; Thread.sleep(10); }
        throw new AssertionError("Child exit not confirmed");
    }
    public static void main(String[] args) {
        try { probe(args); }
        catch (Throwable error) { error.printStackTrace(System.err); System.exit(1); }
    }
    private static void probe(String[] args) throws Exception {
        System.load(args[0]);
        for (boolean input : new boolean[]{true,false}) {
            int[] child=spawn(new String[]{"/system/bin/sh","-c",input?
                "read first; echo READY:$first; read second; echo DONE:$second; cat":
                "if read value; then exit 9; fi; echo EOF"},new String[]{"PATH=/system/bin"},args[1],input);
            check(child!=null,"spawn failed");int pid=child[0];boolean reaped=false;
            try {
                check(child.length==4,"JNI does not expose an input descriptor");
                try (BufferedReader stdout=new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(child[1])),StandardCharsets.UTF_8));
                     InputStream stderr=new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(child[2]))) {
                    if(input) {
                        check(child[3]>=0,"input descriptor missing");
                        try(OutputStream stdin=new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(child[3]))) {
                            stdin.write("first\n".getBytes(StandardCharsets.UTF_8));stdin.flush();
                            check("READY:first".equals(stdout.readLine()),"first exchange failed");
                            check(poll(pid)==-1,"stdin was closed before decision");
                            stdin.write("second\n".getBytes(StandardCharsets.UTF_8));stdin.flush();
                            check("DONE:second".equals(stdout.readLine()),"second exchange failed");
                        }
                    } else {check(child[3]==-1,"default stdin changed");check("EOF".equals(stdout.readLine()),"default stdin must reach EOF");}
                    check(waitExit(pid)==0,"unexpected exit");reap(pid);reaped=true;
                }
                System.out.println("PASS JNI "+(input?"bidirectional input and EOF":"default closed input"));
            } finally {if(!reaped){signalGroup(pid,9);waitExit(pid);reap(pid);}}
        }
        int[] blocked=spawn(new String[]{"/system/bin/sh","-c","read pending"},new String[]{"PATH=/system/bin"},args[1],true);
        check(blocked!=null && blocked.length==4,"pending input setup failed");
        try {
            signalGroup(blocked[0],15);
            check(waitExit(blocked[0])!=0,"cancel reported success");
            System.out.println("PASS JNI cancellation while waiting for input");
        } finally {signalGroup(blocked[0],9);waitExit(blocked[0]);for(int i=1;i<4;i++)if(blocked[i]>=0)ParcelFileDescriptor.adoptFd(blocked[i]).close();reap(blocked[0]);}
        int[] full=spawn(new String[]{"/system/bin/sh","-c","echo READY; sleep 60"},new String[]{"PATH=/system/bin"},args[1],true);
        check(full!=null && full.length==4,"backpressure setup failed");
        AtomicReference<Throwable> failure=new AtomicReference<>();
        try (BufferedReader stdout=new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(full[1])),StandardCharsets.UTF_8));
             InputStream stderr=new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(full[2]));
             OutputStream stdin=new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(full[3]))) {
            check("READY".equals(stdout.readLine()),"backpressure child not ready");
            Thread writer=new Thread(() -> {try {stdin.write(new byte[16*1024*1024]);}catch(Throwable error){failure.set(error);}});
            writer.setDaemon(true);writer.start();Thread.sleep(100);
            check(writer.isAlive(),"write did not wait for pipe capacity");
            signalGroup(full[0],15);check(waitExit(full[0])!=0,"blocked write cancellation reported success");
            writer.join(2000);
            check(!writer.isAlive(),"writer stayed blocked after process group termination");
            check(failure.get() instanceof IOException,"broken input pipe did not report write failure");
            System.out.println("PASS JNI cancellation releases blocked writer and reports failure");
        } finally {signalGroup(full[0],9);waitExit(full[0]);reap(full[0]);}
    }
}
