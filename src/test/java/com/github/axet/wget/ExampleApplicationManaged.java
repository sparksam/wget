package com.github.axet.wget;

import com.github.axet.wget.info.DownloadInfo;
import com.github.axet.wget.info.DownloadInfo.Part;
import com.github.axet.wget.info.ex.DownloadMultipartError;
import rx.Observable;
import rx.schedulers.Schedulers;
import rx.subjects.AsyncSubject;

import java.io.File;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

public class ExampleApplicationManaged {

    AtomicBoolean stop = new AtomicBoolean(false);
    DownloadInfo info;
    long last;
    SpeedInfo speedInfo = new SpeedInfo();

    public static String formatSpeed(long s) {
        if (s > 0.1 * 1024 * 1024 * 1024) {
            float f = s / 1024f / 1024f / 1024f;
            return String.format("%.1f GB", f);
        } else if (s > 0.1 * 1024 * 1024) {
            float f = s / 1024f / 1024f;
            return String.format("%.1f MB", f);
        } else {
            float f = s / 1024f;
            return String.format("%.1f kb", f);
        }
    }

    public static void main(String[] args) {
        ExampleApplicationManaged e = new ExampleApplicationManaged();
        e.run();
    }

    public void run() {
        try {
            Observable notify = AsyncSubject.create(subscriber -> {
                // notify app or save download state
                // you can extract information from DownloadInfo info;
                switch (info.getState()) {
                    case EXTRACTING:
                    case EXTRACTING_DONE:
                        System.out.println(info.getState());
                        break;
                    case DONE:
                        // finish speed calculation by adding remaining bytes speed
                        speedInfo.end(info.getCount());
                        // print speed
                        System.out.println(String.format("%s average speed (%s)", info.getState(), formatSpeed(speedInfo.getAverageSpeed())));
                        break;
                    case RETRYING:
                        System.out.println(info.getState() + " " + info.getDelay());
                        break;
                    case DOWNLOADING:
                        speedInfo.step(info.getCount());
                        long now = System.currentTimeMillis();
                        if (now - 1000 > last) {
                            last = now;

                            String parts = "";

                            if (info.getParts() != null) for (DownloadInfo.Part p : info.getParts()) {
                                if (p.getState().equals(DownloadInfo.Part.States.DOWNLOADING)) {
                                    parts += String.format("Part#%d(%.2f) ", p.getNumber(),
                                            p.getCount() / (float) p.getLength());
                                }
                            }

                            float p = info.getCount() / (float) info.getLength();

                            System.out.println(String.format("%.2f %s (%s / %s)", p, parts, formatSpeed(speedInfo.getCurrentSpeed()),
                                    formatSpeed(speedInfo.getAverageSpeed())));
                        }
                        break;
                    default:
                        break;
                }
                subscriber.onCompleted();
            });
            // choice file
            URL url = new URL("http://download.virtualbox.org/virtualbox/5.0.16/VirtualBox-5.0.16-105871-OSX.dmg");
            // initialize url information object with or without proxy
            // info = new DownloadInfo(url, new ProxyInfo("proxy_addr", 8080, "login", "password"));
            info = new DownloadInfo(url);
            // extract information from the web
            info.extract(stop, notify);
            // enable multipart download
//            info.enableMultipart();
            // Choice target file or set download folder
            File target = new File("/home/samuel/Downloads/VirtualBox-5.0.16-105871-OSX.dmg");
            // create wget downloader
            WGet w = new WGet(info, target, Schedulers.io());
            // init speedinfo
            speedInfo.start(0);
            // will blocks until download finishes
            w.download(stop, notify);
            w.download(new AtomicBoolean(true), notify);
        } catch (DownloadMultipartError e) {
            for (Part p : e.getInfo().getParts()) {
                Throwable ee = p.getException();
                if (ee != null)
                    ee.printStackTrace();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}