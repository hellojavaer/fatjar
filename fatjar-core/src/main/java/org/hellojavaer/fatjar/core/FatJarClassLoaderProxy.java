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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * contains form direct jar, find from local, get from global
 *
 * @author <a href="mailto:hellojavaer@gmail.com">Kaiming Zou</a>,created on 02/03/2017.
 */
public class FatJarClassLoaderProxy extends URLClassLoader {

    private static final Logger     logger             = new Logger();

    private static ClassLoader      j2seClassLoader    = null;

    private boolean                 delegate           = true;
    private boolean                 nestedDelegate     = true;
    private ClassLoader             child              = null;

    private List<FatJarClassLoader> fatJarClassLoaders = new ArrayList<>();

    static {
        if (logger.isDebugEnabled()) {
            logger.debug("FatJarClassLoaderProxy is loaded by " + FatJarClassLoaderProxy.class.getClassLoader());
        }

        //
        ClassLoader cl = String.class.getClassLoader();
        if (cl == null) {
            cl = getSystemClassLoader();
            while (cl.getParent() != null) {
                cl = cl.getParent();
            }
        }
        j2seClassLoader = cl;
        //
    }

    public FatJarClassLoaderProxy(URL[] urls, ClassLoader parent, ClassLoader child, boolean delegate,
                                  boolean nestedDelegate) {
        super(urls, parent);
        this.child = child;
        this.delegate = delegate;
        this.nestedDelegate = nestedDelegate;
        init();
    }

    public FatJarClassLoaderProxy(URL[] urls, ClassLoader parent, ClassLoader child, boolean delegate) {
        super(urls, parent);
        this.child = child;
        this.delegate = delegate;
        init();
    }

    public FatJarClassLoaderProxy(URL[] urls, ClassLoader parent, ClassLoader child) {
        super(urls, parent);
        this.child = child;
        init();
    }

    public FatJarClassLoaderProxy(URL[] urls, ClassLoader parent) {
        super(urls, parent);
        init();
    }

    public FatJarClassLoaderProxy(URL[] urls) {
        super(urls);
        init();
    }

    protected void init() {
        for (URL url : getURLs()) {
            initOneURL(url);
        }
    }

    protected void initOneURL(URL url) {
        List<File> jarFiles = listJarFiles(url);
        if (jarFiles != null) {
            for (File jarFile : jarFiles) {
                try {
                    JarFile jar = new JarFile(jarFile);
                    Manifest manifest = jar.getManifest();
                    if (FatJarClassLoader.isFatJar(manifest)) {
                        URL filePath = jarFile.getCanonicalFile().toURI().toURL();
                        fatJarClassLoaders.add(new FatJarClassLoader(jar, filePath, getParent(), child, nestedDelegate));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private List<File> listJarFiles(URL url) {
        List<File> jarFiles = new ArrayList<>();
        File filePath = new File(url.getFile());
        listJarFiles0(jarFiles, filePath);
        return jarFiles;
    }

    private void listJarFiles0(List<File> jars, File file) {
        if (!file.exists() || !file.canRead()) {
            return;
        }
        if (file.isDirectory()) {
            if (file.getName().startsWith(".")) {
                return;
            } else {
                File[] list = file.listFiles();
                if (list != null) {
                    for (File item : list) {
                        listJarFiles0(jars, item);
                    }
                }
            }
        } else {
            if (file.getName().endsWith(".jar")) {
                jars.add(file);
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[{child:");
        sb.append(child == null ? "null" : child.getClass().getName());
        sb.append("}->{");
        sb.append("own:");
        sb.append(this.getClass().getName());
        sb.append(",delegate:");
        sb.append(delegate);
        sb.append(",nestedDelegate:");
        sb.append(nestedDelegate);
        sb.append("}->{");
        sb.append("parent:");
        sb.append(getParent() == null ? "null" : getParent().getClass().getName());
        sb.append("}]");
        return sb.toString();
    }

    @Override
    public URL findResource(String name) {
        for (FatJarClassLoader internalFatJarClassLoader : fatJarClassLoaders) {
            if (internalFatJarClassLoader.containsResource(name)) {
                URL url = internalFatJarClassLoader.findResource(name);
                if (url != null) {
                    return url;
                }
            }
        }
        return null;
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        LinkedHashSet<URL> result = new LinkedHashSet<URL>();
        for (FatJarClassLoader internalFatJarClassLoader : fatJarClassLoaders) {
            if (internalFatJarClassLoader.containsResource(name)) {
                Enumeration<URL> enumeration = internalFatJarClassLoader.findResources(name);
                if (enumeration != null) {
                    while (enumeration.hasMoreElements()) {
                        result.add(enumeration.nextElement());
                    }
                }
            }
        }
        return Collections.enumeration(result);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        for (FatJarClassLoader internalFatJarClassLoader : fatJarClassLoaders) {
            if (internalFatJarClassLoader.containsResource(name)) {
                Class<?> clazz = internalFatJarClassLoader.findClass(name);
                if (clazz != null) {
                    return clazz;
                }
            }
        }
        return null;
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return loadClass(name, false);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> clazz = null;
        // 0. load by j2se
        try {
            clazz = j2seClassLoader.loadClass(name);
            if (clazz != null) {
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            }
        } catch (ClassNotFoundException e) {
            // ignore;
        }

        // 1. parent delegate
        if (delegate && getParent() != null) {
            clazz = FatJarClassLoader.invokeLoadClass(getParent(), name, resolve);
            if (clazz != null) {
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            }
        }
        // 2.
        for (FatJarClassLoader fatJarClassLoader : fatJarClassLoaders) {
            if (fatJarClassLoader.containsClass(name)) {
                try {
                    clazz = fatJarClassLoader.loadClass(name, resolve);
                    if (clazz != null) {
                        if (resolve) {
                            resolveClass(clazz);
                        }
                        return clazz;
                    }
                } catch (ClassNotFoundException e) {
                    // ignore
                }
            }
        }
        // 3. parent delegate
        if (!delegate && getParent() != null) {
            clazz = FatJarClassLoader.invokeLoadClass(getParent(), name, resolve);
            if (clazz != null) {
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            }
        }
        //
        return null;
    }

    @Override
    public URL getResource(String name) {
        if (delegate && getParent() != null) {
            URL url = getParent().getResource(name);
            if (url != null) {
                return url;
            }
        }
        for (FatJarClassLoader internalFatJarClassLoader : fatJarClassLoaders) {
            if (internalFatJarClassLoader.containsResource(name)) {
                URL url = internalFatJarClassLoader.getResource(name);
                if (url != null) {
                    return url;
                }
            }
        }
        if (!delegate && getParent() != null) {
            URL url = getParent().getResource(name);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        LinkedHashSet<URL> result = new LinkedHashSet<URL>();
        if (delegate && getParent() != null) {
            Enumeration<URL> enumeration = getParent().getResources(name);
            if (enumeration != null) {
                while (enumeration.hasMoreElements()) {
                    result.add(enumeration.nextElement());
                }
            }
        }
        for (FatJarClassLoader internalFatJarClassLoader : fatJarClassLoaders) {
            if (internalFatJarClassLoader.containsResource(name)) {
                Enumeration<URL> enumeration = internalFatJarClassLoader.findResources(name);
                if (enumeration != null) {
                    while (enumeration.hasMoreElements()) {
                        result.add(enumeration.nextElement());
                    }
                }
            }
        }
        if (!delegate && getParent() != null) {
            Enumeration<URL> enumeration = getParent().getResources(name);
            if (enumeration != null) {
                while (enumeration.hasMoreElements()) {
                    result.add(enumeration.nextElement());
                }
            }
        }
        return Collections.enumeration(result);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (delegate && getParent() != null) {
            InputStream inputStream = getParent().getResourceAsStream(name);
            if (inputStream != null) {
                return inputStream;
            }
        }
        for (FatJarClassLoader internalFatJarClassLoader : fatJarClassLoaders) {
            if (internalFatJarClassLoader.containsResource(name)) {
                InputStream inputStream = internalFatJarClassLoader.getResourceAsStream(name);
                if (inputStream != null) {
                    return inputStream;
                }
            }
        }
        if (!delegate && getParent() != null) {
            InputStream inputStream = getParent().getResourceAsStream(name);
            if (inputStream != null) {
                return inputStream;
            }
        }
        return null;
    }

    @Override
    protected void addURL(URL url) {
        super.addURL(url);
        initOneURL(url);
    }

    protected ClassLoader getChild() {
        return child;
    }

    protected boolean isDelegate() {
        return delegate;
    }

    protected boolean isNestedDelegate() {
        return nestedDelegate;
    }
}
