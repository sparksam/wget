package com.github.axet.wget;

import com.github.axet.wget.info.DownloadInfo;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

public class ExampleDirectResume {

    public static void main(String[] args) {
        try {
            // choise internet url (ftp, http)
            URL url = new URL("http://download.virtualbox.org/virtualbox/4.3.28/VirtualBox-4.3.28-100309-OSX.dmg");
            // choise target folder or filename "/Users/axet/Downloads/ap61.ram"
            File targetFile = new File("/home/samuel/vb.dmg");
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