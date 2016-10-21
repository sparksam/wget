package com.github.axet.wget;

import com.github.axet.wget.info.DownloadInfo;
import com.github.axet.wget.info.DownloadInfo.Part;
import com.github.axet.wget.info.DownloadInfo.Part.States;
import com.github.axet.wget.info.URLInfo;
import com.github.axet.wget.info.ex.DownloadInterruptedError;
import com.github.axet.wget.info.ex.DownloadMultipartError;
import com.github.axet.wget.info.ex.DownloadRetry;
import rx.Observable;
import rx.Scheduler;
import rx.subjects.AsyncSubject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

public class DirectMultipart extends Direct {

    static public final int THREAD_COUNT = 3;
    static public final int RETRY_DELAY = 10;

    boolean fatal = false;

    Object lock = new Object();

    public DirectMultipart(DownloadInfo info, File target) {
        super(info, target);
    }

    public DirectMultipart(DownloadInfo info, File target, Scheduler scheduler) {
        super(info, target, scheduler);
    }

    /**
     * check existing file for download resume. for multipart download it may
     * check all parts CRC
     *
     * @param info       download information
     * @param targetFile target file
     * @return return true - if all ok, false - if download can not be restored.
     */
    public static boolean canResume(DownloadInfo info, File targetFile) {
        if (!targetFile.exists())
            return false;

        if (targetFile.length() < info.getCount())
            return false;

        return true;
    }

    /**
     * download part.
     * <p>
     * if returns normally - part is fully donwloaded. other wise - it throws
     * RuntimeException or DownloadRetry or DownloadError
     *
     * @param part   downloading part
     * @param stop   multithread stop command
     * @param notify progress notify call
     */
    Observable<Part> downloadPart(Part part, AtomicBoolean stop, Observable notify) {
        return Observable.create(subscriber -> {
            RandomAccessFile fos = null;
            BufferedInputStream binaryreader = null;

            try {
                long start = part.getStart() + part.getCount();
                long end = part.getEnd();

                // fully downloaded already?
                if (end - start + 1 == 0)
                    return;

                HttpURLConnection conn = info.openConnection();

                File f = target;

                fos = new RandomAccessFile(f, "rw");

                conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                fos.seek(start);

                byte[] bytes = new byte[BUF_SIZE];
                int read = 0;

                RetryWrap.check(conn);

                binaryreader = new BufferedInputStream(conn.getInputStream());

                boolean localStop = false;

                while ((read = binaryreader.read(bytes)) > 0) {
                    // ensure we do not download more then part size.
                    // if so cut bytes and stop download
                    long partEnd = part.getLength() - part.getCount();
                    if (read > partEnd) {
                        read = (int) partEnd;
                        localStop = true;
                    }

                    fos.write(bytes, 0, read);
                    part.setCount(part.getCount() + read);
                    info.calculate();
                    notify.subscribe();

                    if (stop.get())
                        throw new DownloadInterruptedError("stop");
                    if (Thread.interrupted())
                        throw new DownloadInterruptedError("interrupted");
                    if (fatal())
                        throw new DownloadInterruptedError("fatal");

                    // do not throw exception here. we normally done downloading.
                    // just took a littlbe bit more
                    if (localStop)
                        return;
                }

                if (part.getCount() != part.getLength())
                    throw new DownloadRetry("EOF before end of part");
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (binaryreader != null)
                    try {
                        binaryreader.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                if (fos != null)
                    try {
                        fos.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
            }
            subscriber.onCompleted();
        });

    }

    boolean fatal() {
        synchronized (lock) {
            return fatal;
        }
    }

    void fatal(boolean b) {
        synchronized (lock) {
            fatal = b;
        }
    }

    String trimLen(String str, int len) {
        if (str.length() > len)
            return str.substring(0, len / 2) + "..." + str.substring(str.length() - len / 2, str.length());
        else
            return str;
    }

    Observable<Part> downloadWorker(final Part p, final AtomicBoolean stop, final Observable notify) {
        p.setState(States.DOWNLOADING);
        System.out.println("Part" + p.getNumber());
        return Observable.create(subscriber -> {
            try {
                RetryWrap.wrap(stop, new RetryWrap.Wrap() {

                    @Override
                    public void proxy() {
                        info.getProxy().set();
                    }

                    @Override
                    public void download() throws IOException {
                        p.setState(States.DOWNLOADING);
                        notify.subscribe();
                        downloadPart(p, stop, notify).subscribe();
                    }

                    @Override
                    public void retry(int delay, Throwable e) {
                        p.setDelay(delay, e);
                        notify.subscribe();
                    }

                    @Override
                    public void moved(URL url) {
                        p.setState(States.RETRYING);
                        notify.subscribe();
                    }

                });
                p.setState(States.DONE);
                notify.subscribe();
            } catch (DownloadInterruptedError e) {
                p.setState(States.STOP, e);
                subscriber.onError(e);
                notify.subscribe();

                fatal(true);
            } catch (RuntimeException e) {
                p.setState(States.ERROR, e);
                subscriber.onError(e);
                notify.subscribe();

                fatal(true);
            }
            subscriber.onCompleted();
        });
//        return p;
    }

    /**
     * return next part to download. ensure this part is not done() and not
     * currently downloading
     *
     * @return
     */
    Part getPart() {
        for (Part p : info.getParts()) {
            if (!p.getState().equals(States.QUEUED))
                continue;
            return p;
        }

        return null;
    }

    /**
     * return all parts to download. Ensure those parts are not done() and nor currently downoalding
     *
     * @return
     */
    Collection<Part> getParts() {
        ArrayList<Part> parts = new ArrayList<>();
        info.getParts().stream().
                filter(part -> part.getState().equals(States.QUEUED)).forEach(parts::add);
        return parts;
    }

    /**
     * return true, when thread pool empty, and here is no unfinished parts to
     * download
     *
     * @return true - done. false - not done yet
     * @throws InterruptedException
     */
    boolean done(AtomicBoolean stop) {
        if (stop.get())
            throw new DownloadInterruptedError("stop");
        if (Thread.interrupted())
            throw new DownloadInterruptedError("interupted");
//        if (worker.active())
//            return false;
        if (!getParts().isEmpty())
            return false;

        return true;
    }

    @Override
    public void download(AtomicBoolean stop, Observable notify) {
        for (Part p : info.getParts()) {
            if (p.getState().equals(States.DONE))
                continue;
            p.setState(States.QUEUED);
        }
        info.setState(URLInfo.States.DOWNLOADING);
        notify.subscribe();

        try {
            if (!done(stop)) {
                //TODO Review error handling
                AsyncSubject.from(getParts()).flatMap(p -> downloadWorker(p, stop, notify).subscribeOn(scheduler))
                        .subscribeOn(scheduler).toBlocking().subscribe();
//                Part p = getPart();
//                if (p != null) {
//                    downloadWorker(p, stop, notify).toBlocking().subscribe();
//                } else {
//                    // we have no parts left.
//                    //
//                    // wait until task ends and check again if we have to retry.
//                    // we have to check if last part back to queue in case of
//                    // RETRY state
//                }

                // if we start to receive errors. stop add new tasks and wait
                // until all active tasks be emptied
                if (fatal()) {

                    // check if all parts finished with interrupted, throw one
                    // interrupted
                    {
                        boolean interrupted = true;
                        for (Part pp : info.getParts()) {
                            Throwable e = pp.getException();
                            if (e == null)
                                continue;
                            if (e instanceof DownloadInterruptedError)
                                continue;
                            interrupted = false;
                        }
                        if (interrupted)
                            throw new DownloadInterruptedError("multipart all interrupted");
                    }

                    // ok all thread stopped. now throw the exception and let
                    // app deal with the errors
                    throw new DownloadMultipartError(info);
                }
            }

            info.setState(URLInfo.States.DONE);
            notify.subscribe();
        }
//        catch (InterruptedException e) {
//            info.setState(URLInfo.States.STOP);
//            notify.subscribe();
//
//            throw new DownloadInterruptedError(e);
//        }
        catch (DownloadInterruptedError e) {
            info.setState(URLInfo.States.STOP);
            notify.subscribe();

            throw e;
        } catch (RuntimeException e) {
            info.setState(URLInfo.States.ERROR);
            notify.subscribe();
            throw e;
        } finally {
        }
    }
}
