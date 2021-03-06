/*
 * Copyright 2017-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hellojavaer.fatjar.core;

import java.io.*;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.cert.Certificate;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * rewrite protocol jar, but not rewrite protocol file 
 *
 * @author <a href="mailto:hellojavaer@gmail.com">Kaiming Zou</a>,created on 12/03/2017.
 */
class FatJarURLStreamHandler extends URLStreamHandler {

    private static final Logger logger                   = new Logger();

    private static final String SEPARATOR                = "!/";

    private static final String FILE_PROTOCOL            = "file:";

    private URLStreamHandler    fallbackURLStreamHandler = null;

    static {
        if (logger.isDebugEnabled()) {
            logger.debug("FatJarURLStreamHandler is loaded by " + FatJarURLStreamHandler.class.getClassLoader());
        }
        //
        Class<?> clazz = FatJarURLConnection.class;
    }

    public FatJarURLStreamHandler() {
    }

    public FatJarURLStreamHandler(URLStreamHandler fallbackURLStreamHandler) {
        this.fallbackURLStreamHandler = fallbackURLStreamHandler;
    }

    @Override
    protected URLConnection openConnection(URL u) throws IOException {
        try {
            return new FatJarURLConnection(u);
        } catch (Exception e) {
            if (fallbackURLStreamHandler != null) {
                Method method = FatJarReflectionUtils.getMethod(fallbackURLStreamHandler.getClass(), "openConnection",
                                                                URL.class);
                try {
                    return (URLConnection) method.invoke(fallbackURLStreamHandler, u);
                } catch (Exception e1) {
                    if (e1 instanceof RuntimeException) {
                        throw (RuntimeException) e1;
                    } else {
                        throw new RuntimeException(e);
                    }
                }
            } else {
                if (e instanceof IOException || e instanceof RuntimeException) {
                    throw e;
                } else {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static class FatJarURLConnection extends JarURLConnection {

        private JarFile            jarFile;
        private String             entryName;
        private Manifest           manifest;
        private JarEntry           jarEntry;
        private boolean            normalJarUrl;
        private static Set<String> notFoundResources = new HashSet<>();

        static {
            Class<?> clazz = JarURLInputStream.class;
        }

        protected FatJarURLConnection(URL url) throws IOException {
            super(url);
            if (url.getFile().indexOf("!/") == -1) {
                throw new NullPointerException("no !/ in spec");
            }
        }

        @Override
        public JarFile getJarFile() throws IOException {
            this.connect();
            if (jarFile == null) {
                throw new FileNotFoundException("JAR entry " + this.entryName + " not found in "
                                                + this.jarFile.getName());
            } else {
                return jarFile;
            }
        }

        @Override
        public void connect() throws IOException {
            if (!this.connected) {
                synchronized (this) {
                    if (!this.connected) {
                        String fileString = url.getFile();
                        if (notFoundResources.contains(fileString)) {
                            return;
                        }

                        String[] pathSections = fileString.split(SEPARATOR);
                        String rootFileDir = pathSections[0].substring(FILE_PROTOCOL.length());
                        if (fileString.endsWith("!/")) {
                            this.entryName = null;
                        } else {
                            if (pathSections.length <= 2) {// normal jar protocol
                                this.normalJarUrl = true;
                            }
                            this.entryName = pathSections[pathSections.length - 1];
                        }

                        JarFile jarFile = null;
                        if (!normalJarUrl) {//
                            jarFile = FatJarTempFileManager.getJarFile(fileString);
                        }
                        if (jarFile == null) {
                            File file = new File(rootFileDir);
                            if (file.isDirectory()) {
                                throw new FileNotFoundException(rootFileDir + " (Is a directory)");
                            }
                            jarFile = new JarFile(file);
                            String tempPath = rootFileDir;// file:/a/b/c
                            for (int i = 1; i < pathSections.length - 1; i++) {//
                                String entryName0 = pathSections[i];
                                tempPath = tempPath + SEPARATOR + entryName0;
                                JarEntry jarEntry = jarFile.getJarEntry(entryName0);
                                if (jarEntry == null) {
                                    notFoundResources.add(tempPath);
                                    return;
                                } else {
                                    jarFile = FatJarTempFileManager.buildJarFile(tempPath, jarEntry.getTime(),
                                                                                 jarFile.getInputStream(jarEntry));
                                }
                            }
                        }
                        this.jarFile = jarFile;
                        this.manifest = jarFile.getManifest();
                        if (this.entryName != null) {
                            this.jarEntry = jarFile.getJarEntry(entryName);
                        }
                        //
                        this.connected = true;
                    }
                }
            }
        }

        @Override
        public InputStream getInputStream() throws IOException {
            connect();
            if (this.entryName == null) {
                throw new IOException("no entry name specified");
            } else {
                return new JarURLInputStream(getJarFile().getInputStream(getJarEntry()));
            }
        }

        @Override
        public URL getJarFileURL() {
            return super.getJarFileURL();
        }

        @Override
        public String getEntryName() {
            return entryName;
        }

        @Override
        public Manifest getManifest() throws IOException {
            this.connect();
            return manifest;
        }

        @Override
        public JarEntry getJarEntry() throws IOException {
            this.connect();
            if (jarEntry == null) {
                throw new IOException("no entry name specified");
            } else {
                return jarEntry;
            }
        }

        @Override
        public Attributes getAttributes() throws IOException {
            this.connect();
            return getJarEntry().getAttributes();
        }

        @Override
        public Attributes getMainAttributes() throws IOException {
            this.connect();
            if (manifest == null) {
                return null;
            } else {
                return manifest.getMainAttributes();
            }
        }

        @Override
        public Certificate[] getCertificates() throws IOException {
            this.connect();
            return getJarEntry().getCertificates();
        }

        class JarURLInputStream extends FilterInputStream {

            JarURLInputStream(InputStream inputStream) {
                super(inputStream);
            }

            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    if (FatJarURLConnection.this.normalJarUrl) {
                        jarFile.close();
                    }
                }
            }
        }
    }
}
