package com.github.axet.wget;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

public class ExampleDirectDownload {

    public static void main(String[] args) {
        try {
            // choise internet url (ftp, http)
            URL url = new URL("http://download.virtualbox.org/virtualbox/5.0.16/VirtualBox-5.0.16-105871-OSX.dmg");
            // choise target folder or filename "/Users/axet/Downloads/ap61.ram"
            File target = new File("/home/samuel/Downloads/");
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