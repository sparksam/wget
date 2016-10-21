package com.github.axet.wget;

import com.github.axet.wget.info.DownloadInfo;
import com.github.axet.wget.info.ex.DownloadInterruptedError;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import rx.Observable;
import rx.Scheduler;
import rx.subjects.AsyncSubject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WGet {

    Direct d;
    File targetFile;
    private DownloadInfo info;

    /**
     * download with events control.
     *
     * @param source
     * @param target
     */
    public WGet(URL source, File target) {
        create(source, target);
    }

    /**
     * download with events control.
     *
     * @param source
     * @param target
     * @param scheduler
     */
    public WGet(URL source, File target, Scheduler scheduler) {
        this(source, target);
        createDirect(scheduler);
    }

    /**
     * application controlled download / resume. you should specify targetfile name exactly. since you are choice resume
     * / multipart download. application unable to control file name choice / creation.
     *
     * @param info       download info
     * @param targetFile target files
     */

    public WGet(DownloadInfo info, File targetFile, Scheduler scheduler) {
        this(info, targetFile);
        createDirect(scheduler);
    }

    public WGet(DownloadInfo info, File targetFile) {
        this.info = info;
        this.targetFile = targetFile;
        create();
    }

    public static File calcName(URL source, File target) {
        DownloadInfo info = new DownloadInfo(source);
        info.extract();

        return calcName(info, target);
    }

    public static File calcName(DownloadInfo info, File target) {
        // target -
        // 1) can point to directory.
        // - generate exclusive (1) name.
        // 2) to exisiting file
        // 3) to non existing file

        String name = null;

        name = info.getContentFilename();

        if (name == null)
            name = new File(info.getSource().getPath()).getName();

        try {
            name = URLDecoder.decode(name, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String nameNoExt = FilenameUtils.removeExtension(name);
        String ext = FilenameUtils.getExtension(name);

        File targetFile = null;

        if (target.isDirectory()) {
            targetFile = FileUtils.getFile(target, name);
            int i = 1;
            while (targetFile.exists()) {
                targetFile = FileUtils.getFile(target, nameNoExt + " (" + i + ")." + ext);
                i++;
            }
        } else {
            try {
                FileUtils.forceMkdir(new File(target.getParent()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            targetFile = target;
        }

        return targetFile;
    }

    public static String getHtml(URL source) {
        return getHtml(source, new HtmlLoader() {
            @Override
            public void notifyRetry(int delay, Throwable e) {
            }

            @Override
            public void notifyDownloading() {
            }

            @Override
            public void notifyMoved() {
            }
        }, new AtomicBoolean(false));
    }

    public static String getHtml(DownloadInfo info) {
        return getHtml(info, new HtmlLoader() {
            @Override
            public void notifyRetry(int delay, Throwable e) {
            }

            @Override
            public void notifyDownloading() {
            }

            @Override
            public void notifyMoved() {
            }
        }, new AtomicBoolean(false));
    }

    public static String getHtml(final URL source, final HtmlLoader load, final AtomicBoolean stop) {
        return getHtml(new DownloadInfo(source), load, stop);
    }

    public static String getHtml(final DownloadInfo source, final HtmlLoader load, final AtomicBoolean stop) {
        String html = RetryWrap.wrap(stop, new RetryWrap.WrapReturn<String>() {
            DownloadInfo info = source;

            @Override
            public void proxy() {
                info.getProxy().set();
            }

            @Override
            public void retry(int delay, Throwable e) {
                load.notifyRetry(delay, e);
            }

            @Override
            public String download() throws IOException {
                HttpURLConnection conn = info.openConnection();

                RetryWrap.check(conn);

                return getHtml(conn, stop);
            }

            @Override
            public void moved(URL url) {
                DownloadInfo old = info;
                info = new DownloadInfo(url);
                info.setReferer(old.getReferer());

                load.notifyMoved();
            }

        });

        return html;
    }

    public static String getHtml(HttpURLConnection conn, AtomicBoolean stop) throws IOException {
        InputStream is = conn.getInputStream();

        String enc = conn.getContentEncoding();

        if (enc == null) {
            Pattern p = Pattern.compile("charset=(.*)");
            Matcher m = p.matcher(conn.getHeaderField("Content-Type"));
            if (m.find()) {
                enc = m.group(1);
            }
        }

        if (enc == null)
            enc = "UTF-8";

        BufferedReader br = new BufferedReader(new InputStreamReader(is, enc));

        String line = null;

        StringBuilder contents = new StringBuilder();
        while ((line = br.readLine()) != null) {
            contents.append(line);
            contents.append("\n");

            if (stop.get())
                throw new DownloadInterruptedError("stop");
            if (Thread.currentThread().isInterrupted())
                throw new DownloadInterruptedError("interrupted");
        }

        return contents.toString();
    }

    void create(URL source, File target, Scheduler scheduler) {
        create(source, target);
        createDirect(scheduler);
    }

    void create(URL source, File target) {
        info = new DownloadInfo(source);
        info.extract();
        create(target);
    }

    void create(File target) {
        targetFile = calcName(info, target);
        create();
    }

    void create() {
        d = createDirect(null);
    }

    void create(Scheduler scheduler) {
        d = createDirect(scheduler);
    }

    Direct createDirect(Scheduler scheduler) {
        if (info.multipart()) {
            return new DirectMultipart(info, targetFile, scheduler);
        } else if (info.getRange()) {
            return new DirectRange(info, targetFile, scheduler);
        } else {
            return new DirectSingle(info, targetFile, scheduler);
        }
    }

    public void download() {
        download(new AtomicBoolean(false), AsyncSubject.create());
    }

    public void download(AtomicBoolean stop, Observable notify) {
        d.download(stop, notify);
    }

    public DownloadInfo getInfo() {
        return info;
    }

    public interface HtmlLoader {
        /**
         * some socket problem, retyring
         *
         * @param delay
         * @param e
         */
        public void notifyRetry(int delay, Throwable e);

        /**
         * start downloading
         */
        public void notifyDownloading();

        /**
         * document moved, relocating
         */
        public void notifyMoved();
    }
}
