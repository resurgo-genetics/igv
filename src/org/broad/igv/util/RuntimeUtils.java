/*
 * Copyright (c) 2007-2012 The Broad Institute, Inc.
 * SOFTWARE COPYRIGHT NOTICE
 * This software and its documentation are the copyright of the Broad Institute, Inc. All rights are reserved.
 *
 * This software is supplied without any warranty or guaranteed support whatsoever. The Broad Institute is not responsible for its use, misuse, or functionality.
 *
 * This software is licensed under the terms of the GNU Lesser General Public License (LGPL),
 * Version 2.1 which is available at http://www.opensource.org/licenses/lgpl-2.1.php.
 */

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.broad.igv.util;

import org.apache.log4j.Logger;
import org.broad.igv.DirectoryManager;
import org.broad.igv.ui.util.MessageUtils;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author jrobinso
 */
public class RuntimeUtils {

    private static Logger log = Logger.getLogger(RuntimeUtils.class);

    public static long getAvailableMemory() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long allocatedMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        return freeMemory + (maxMemory - allocatedMemory);
    }

    public static double getAvailableMemoryFraction() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long allocatedMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        return (double) ((freeMemory + (maxMemory - allocatedMemory))) / maxMemory;

    }

    /**
     * Start an external process with the provided message.
     * Also starts a separate thread to read back error stream
     * <p/>
     * See {@link Runtime#exec(String, String[], java.io.File)} for explanation of arguments
     *
     * @return
     */
    public static Process startExternalProcess(String[] msg, String[] envp, File dir) throws IOException {
        Process pr = Runtime.getRuntime().exec(msg, envp, dir);
        startErrorReadingThread(pr);
        return pr;
    }

    private static Process startErrorReadingThread(Process pr) {
        final BufferedReader err = new BufferedReader(new InputStreamReader(pr.getErrorStream()));

        //Supposed to read error stream on separate thread to prevent blocking
        Thread runnable = new Thread() {

            private boolean messageDisplayed = false;

            @Override
            public void run() {
                String line;
                try {
                    while ((line = err.readLine()) != null) {
                        log.error(line);
                        if (!messageDisplayed && line.toLowerCase().contains("error")) {
                            MessageUtils.showMessage(line + "<br>See igv.log for more details");
                            messageDisplayed = true;
                        }
                    }
                    err.close();
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            }
        };
        runnable.start();
        return pr;
    }

    /**
     * @param cmd
     * @param envp
     * @param dir
     * @return
     * @throws java.io.IOException
     * @deprecated Use {@link #executeShellCommand(String[], String[], java.io.File)}
     */
    @Deprecated
    public static String executeShellCommand(String cmd, String[] envp, File dir) throws IOException {
        return executeShellCommand(new String[]{cmd}, envp, dir);
    }


    public static String executeShellCommand(String cmd[], String[] envp, File dir) throws IOException {
        return executeShellCommand(cmd, envp, dir, true);
    }

    public static String executeShellCommand(String cmd[], String[] envp, File dir, boolean waitFor) throws IOException {
        Process pr = startExternalProcess(cmd, envp, dir);

        if(waitFor){
            try {
                pr.waitFor();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        InputStream inputStream = null;
        String line = "";

        try {
            inputStream = pr.getInputStream();
            BufferedReader buf = new BufferedReader(new InputStreamReader(inputStream));
            StringWriter writer = new StringWriter();
            PrintWriter pw = new PrintWriter(writer);
            while ((line = buf.readLine()) != null) {
                pw.println(line);
            }
            pw.close();
            return writer.toString();
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
            OutputStream os = pr.getOutputStream();
            if(os != null){
                os.close();
            }
        }
    }

    /**
     * Converts input string paths to file URLs
     * @param paths
     * @return
     */
    private static URL[] pathsToURLs(String[] paths) {
        URL[] urls = new URL[paths.length];
        for (int pp = 0; pp < paths.length; pp++) {
            try {
                urls[pp] = new URL("file://" + paths[0]);
            } catch (MalformedURLException e) {
                log.error(e);
            }
        }
        return urls;
    }

    /**
     * Returns an array of builtin library URL locations, as well as those passed in with {@code libURLs}
     * @param libURLs
     * @return
     */
    private static URL[] addBuiltinURLs(URL[] libURLs) {
        String[] builtin_paths = new String[1];
        builtin_paths[0] = (new File(DirectoryManager.getIgvDirectory(), "plugins/")).getAbsolutePath();
        URL[] builtin_urls = pathsToURLs(builtin_paths);

        List<URL> outURLs = new ArrayList<URL>(Arrays.asList(libURLs != null ? libURLs : new URL[0]));
        outURLs.addAll(Arrays.asList(builtin_urls));

        return outURLs.toArray(new URL[outURLs.size()]);
    }

    private static URL[] getClassURLs(URL[] libURLs){
        return addBuiltinURLs(libURLs);
    }

    /**
     *  Create {@link java.lang.Class} object with the desired name,
     *  looking in {@code libURLs} as well as built-in locations
     * @param className
     * @param libURLs
     * @return
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws ClassNotFoundException
     */
    public static Class loadClassForName(String className, URL[] libURLs) throws IllegalAccessException, InstantiationException, ClassNotFoundException {

        Class clazz = null;
        //Easy way
        try {
            clazz = Class.forName(className);
        } catch (ClassNotFoundException e) {
            //Try with custom loader below
        }
        if (clazz != null) return clazz;

        //If not found, check other locations

        ClassLoader loader = URLClassLoader.newInstance(
                getClassURLs(libURLs),
                ClassLoader.getSystemClassLoader()
        );
        return loader.loadClass(className);
    }

    /**
     * Create an instance of the specified class. Must have no-arg constructor
     * @param className
     * @param libURLs
     * @return
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws ClassNotFoundException
     */
    public static Object loadInstanceForName(String className, URL[] libURLs) throws IllegalAccessException, InstantiationException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException {

        Class clazz = loadClassForName(className, libURLs);
        Constructor constructor = clazz.getConstructor();
        return constructor.newInstance();
    }


}
