# wget

wget direct / multithread / singlethread java download library.

Support single thread, single thread with download continue / resume, and multithread downloads.

## Features

1) Handles for HTTP errors / redirects

2) Multipart / multithread downloads

3) Handle for Content-Disposition (remote file name)

4) Support for server RANGE feature (resume downloads / multipart logic)

5) Proxy support

6) Speed measurments

7) Handle HTML redirects

## Exceptions

Here is a five kind of exceptions.

1) Fatal exception. all RuntimeException's
  We shall stop application

2) DownloadError (extends RuntimeException)
  We unable to process following url and shall stop to download it. It may be rised by problem with local file.

3) DownloadMultipartError (extends DownloadError)
  We unable to download multhread source. Shall stop downloading and parse each Parts exceptions.

4) DownloadInterrceptedError (extends DownloadError)
  Current thread was interrcepted by main app (you). So handle it your self ;)
  
5) DownloadIOError (extends DownloadError)
  Some simple exceptoins, like Timeout exceptions we handle internaly, and retry part / download automaticaly without
  user interrraction. But some hudge errors, like problems with file on server (HTTP 403) we pass to the App.
  It may stop download, or auto update download URL and automaticaly retry the download without any user interaction.


## Example Direct Download

```java
package com.github.axet.wget;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

import com.github.axet.wget.WGet;

public class Example {

    public static void main(String[] args) {
        try {
            // choise internet url (ftp, http)
            URL url = new URL("http://www.dd-wrt.com/routerdb/de/download/D-Link/DIR-300/A1/ap61.ram/2049");
            // choise target folder or filename "/Users/axet/Downloads/ap61.ram"
            File target = new File("/Users/axet/Downloads/");
            // initialize wget object
            WGet w = new WGet(url, target);
            // single thread download. will return here only when file download
            // is complete (or error raised).
            w.download();
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (RuntimeException allDownloadExceptions) {
            allDownloadExceptions.printStackTrace();
        }
    }
}
```

## Resume Download

```java
package com.github.axet.wget;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

import com.github.axet.wget.info.DownloadInfo;

public class ExampleDirectResume {

    public static void main(String[] args) {
        try {
            // choise internet url (ftp, http)
            URL url = new URL("http://download.virtualbox.org/virtualbox/4.3.28/VirtualBox-4.3.28-100309-OSX.dmg");
            // choise target folder or filename "/Users/axet/Downloads/ap61.ram"
            File targetFile = new File("/Users/x/Downloads/vb.dmg");
            // get file remote information
            DownloadInfo info = new DownloadInfo(url);
            info.extract();
            // initialize wget object
            WGet w = new WGet(info, targetFile);
            // single thread download. will return here only when file download
            // is complete (or error raised).
            w.download();
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (RuntimeException allDownloadExceptions) {
            allDownloadExceptions.printStackTrace();
        }
    }
}
```

## Application Managed Multithread Download

```java
package com.github.axet.wget;

import java.io.File;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.axet.wget.info.DownloadInfo;
import com.github.axet.wget.info.DownloadInfo.Part;
import com.github.axet.wget.info.DownloadInfo.Part.States;
import com.github.axet.wget.info.ex.DownloadMultipartError;

public class ExampleApplicationManaged {

    AtomicBoolean stop = new AtomicBoolean(false);
    DownloadInfo info;
    long last;
    SpeedInfo speedInfo = new SpeedInfo();

    public static String formatSpeed(long s) {
        if (s > 0.1 * 1024 * 1024 * 1024) {
            float f = s / 1024f / 1024f / 1024f;
            return String.format("%.1f GB/s", f);
        } else if (s > 0.1 * 1024 * 1024) {
            float f = s / 1024f / 1024f;
            return String.format("%.1f MB/s", f);
        } else {
            float f = s / 1024f;
            return String.format("%.1f kb/s", f);
        }
    }

    public void run() {
        try {
            Runnable notify = new Runnable() {
                @Override
                public void run() {
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

                            for (Part p : info.getParts()) {
                                if (p.getState().equals(States.DOWNLOADING)) {
                                    parts += String.format("Part#%d(%.2f) ", p.getNumber(),
                                            p.getCount() / (float) p.getLength());
                                }
                            }

                            float p = info.getCount() / (float) info.getLength();

                            System.out.println(String.format("%.2f %s (%s / %s)", p, parts,
                                    formatSpeed(speedInfo.getCurrentSpeed()),
                                    formatSpeed(speedInfo.getAverageSpeed())));
                        }
                        break;
                    default:
                        break;
                    }
                }
            };

            // choice file
            URL url = new URL("http://download.virtualbox.org/virtualbox/5.0.16/VirtualBox-5.0.16-105871-OSX.dmg");
            // initialize url information object with or without proxy
            // info = new DownloadInfo(url, new ProxyInfo("proxy_addr", 8080, "login", "password"));
            info = new DownloadInfo(url);
            // extract information from the web
            info.extract(stop, notify);
            // enable multipart download
            info.enableMultipart();
            // Choice target file or set download folder
            File target = new File("/Users/axet/Downloads/VirtualBox-5.0.16-105871-OSX.dmg");
            // create wget downloader
            WGet w = new WGet(info, target);
            // init speedinfo
            speedInfo.start(0);
            // will blocks until download finishes
            w.download(stop, notify);
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

    public static void main(String[] args) {
        ExampleApplicationManaged e = new ExampleApplicationManaged();
        e.run();
    }
}
```

## Cetral Maven Repo

```xml
<dependency>
  <groupId>com.github.axet</groupId>
  <artifactId>wget</artifactId>
  <version>1.3.0</version>
</dependency>
```
