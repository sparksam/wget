package com.github.axet.wget;

import com.github.axet.wget.info.DownloadInfo;
import rx.Observable;
import rx.Scheduler;
import rx.schedulers.Schedulers;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class Direct {

    /**
     * size of read buffer
     */
    static public final int BUF_SIZE = 4 * 1024;
    File target = null;
    DownloadInfo info;
    Scheduler scheduler;

    /**
     * @param info   download file information
     * @param target target file
     */
    public Direct(DownloadInfo info, File target) {
        this.target = target;
        this.info = info;
    }

    /**
     * @param info      download file information
     * @param target    target file
     * @param scheduler
     */
    public Direct(DownloadInfo info, File target, Scheduler scheduler) {
        this(info, target);
        if (scheduler != null) this.scheduler = scheduler;
        else this.scheduler = Schedulers.io();
    }

    /**
     * @param stop   multithread stop command
     * @param notify progress notify call
     */
    abstract public void download(AtomicBoolean stop, Observable notify);

}
