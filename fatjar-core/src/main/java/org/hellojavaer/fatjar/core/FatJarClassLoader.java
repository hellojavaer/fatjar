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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessControlException;
import java.security.CodeSource;
import java.security.Policy;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 *
 * The implement of this class refered to org.apache.catalina.loader.WebappClassLoaderBase
 *
 * 
 * 
 * @author <a href="mailto:hellojavaer@gmail.com">Kaiming Zou</a>,created on 02/03/2017.
 */
public class FatJarClassLoader extends URLClassLoader {

    private static final String               JAR_PROTOCOL         = "jar:";
    private static final String               CLASSS_SUBFIX        = ".class";
    private static final String               SEPARATOR            = "!/";

    private static Logger                     logger               = LoggerFactory.getLogger(FatJarClassLoader.class);

    private boolean                           delegate             = false;

    private JarFile                           currentJar;
    private List<JarFile>                     dependencyJars       = new ArrayList<>();

    private List<FatJarClassLoader>           subClassLoaders      = new ArrayList<>();

    private Map<String, ResourceEntry>        loadedResources      = new HashMap<>();
    private Set<String>                       notFoundResources    = new HashSet<>();

    private String                            filePathPrefix       = "";

    private ClassLoader                       child;

    private ConcurrentHashMap<String, Object> lockMap              = new ConcurrentHashMap<>();

    private URL                               fatJarURL;

    private static ClassLoader                j2seClassLoader      = null;
    private static SecurityManager            securityManager      = null;
    private static Method                     FIND_CLASS_METHOD    = null;
    private static Method                     FIND_RESOURCE_METHOD = null;

    static {
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
        securityManager = System.getSecurityManager();
        if (securityManager != null) {
            try {
                Policy policy = Policy.getPolicy();
                policy.refresh();
            } catch (AccessControlException e) {
                // ignore
            }
        }
        //
        try {
            FIND_CLASS_METHOD = ClassLoader.class.getDeclaredMethod("findClass", String.class);
            FIND_CLASS_METHOD.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        //
        try {
            FIND_RESOURCE_METHOD = ClassLoader.class.getDeclaredMethod("findResource", String.class);
            FIND_RESOURCE_METHOD.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public FatJarClassLoader(URL[] urls, ClassLoader parent, ClassLoader child, boolean delegate) {
        super(urls, parent);
        this.child = child;
        this.delegate = delegate;
        init();
    }

    public FatJarClassLoader(URL[] urls, ClassLoader parent, ClassLoader child) {
        super(urls, parent);
        useSystemConfig();
        this.child = child;
        init();
    }

    public FatJarClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
        useSystemConfig();
        init();
    }

    public FatJarClassLoader(URL[] urls) {
        super(urls);
        useSystemConfig();
        init();
    }

    private void useSystemConfig() {
        Boolean deleaget = FatJarSystemConfig.isLoadDelegate();
        if (deleaget != null) {
            this.delegate = deleaget;
        }
    }

    private void init() {
        for (URL url : getURLs()) {
            List<File> jarFiles = listJarFiles(url);
            if (jarFiles != null) {
                for (File jarFile : jarFiles) {
                    try {
                        JarFile jar = new JarFile(jarFile);
                        Manifest manifest = jar.getManifest();
                        if (isFatJar(manifest)) {
                            URL filePath = jarFile.getCanonicalFile().toURI().toURL();
                            subClassLoaders.add(new FatJarClassLoader(jar, getParent(), child, false,
                                                                      filePath.toString()));
                        }
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
        }
    }

    private List<File> listJarFiles(URL url) {
        List<File> jarFiles = new ArrayList<>();
        File filePath = new File(url.getPath());
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

    private FatJarClassLoader(JarFile currentJar, ClassLoader parent, ClassLoader child, boolean delegate,
                              String filePathPrefix) {
        super(new URL[0], parent);
        this.currentJar = currentJar;
        this.filePathPrefix = filePathPrefix;
        this.delegate = delegate;
        try {
            this.fatJarURL = new URL(filePathPrefix);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        Enumeration<JarEntry> jarEntries = currentJar.entries();
        if (jarEntries != null) {
            while (jarEntries.hasMoreElements()) {
                JarEntry jarEntry = jarEntries.nextElement();
                if (isDependencyLib(jarEntry)) {
                    try {
                        InputStream inputStream = currentJar.getInputStream(jarEntry);
                        String nextPrefix = filePathPrefix + SEPARATOR + jarEntry.getName();
                        JarFile subJarFile = FatJarTempFileManager.buildJarFile(nextPrefix, jarEntry.getName(),
                                                                                inputStream);
                        Manifest manifest = subJarFile.getManifest();
                        if (isFatJar(manifest)) {
                            subClassLoaders.add(new FatJarClassLoader(subJarFile, parent, child, delegate, nextPrefix));
                        } else {
                            dependencyJars.add(subJarFile);
                        }
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                }// else ignore
            }
        }
    }

    protected boolean isFatJar(Manifest manifest) {
        if (manifest != null) {
            Attributes attributes = manifest.getMainAttributes();
            String fatJarVersion = attributes.getValue("Fat-Jar-Version");
            if (fatJarVersion != null) {
                return true;
            }
        }
        return false;
    }

    private boolean isDependencyLib(JarEntry jarEntry) {
        String name = jarEntry.getName();
        if (!jarEntry.isDirectory() && name.endsWith(".jar")) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> clazz = null;
        synchronized (getLockObject(name)) {
            // 0. find in local cache
            ResourceEntry resource = loadedResources.get(name);
            if (resource != null) {
                clazz = resource.getClazz();
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            }

            // 1. load by j2se
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

            // 2. parent delegate
            if (delegate) {
                try {
                    clazz = Class.forName(name, false, getParent());
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

            // 3.0 load from local resources
            try {
                clazz = findClassInternal(name);
                if (clazz != null) {
                    if (resolve) {
                        resolveClass(clazz);
                    }
                    return clazz;
                }
            } catch (ClassNotFoundException e) {
                // ignore
            }
            // 3.1 load by sub-classload which will recursive find in fat-jar
            if (subClassLoaders != null) {
                for (FatJarClassLoader fatJarClassLoader : subClassLoaders) {
                    if (fatJarClassLoader.containsClass(name)) {
                        try {
                            clazz = Class.forName(name, false, fatJarClassLoader);
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
            }

            // 4. nested class delegate to child
            if (currentJar == null && child != null) {
                try {
                    clazz = (Class<?>) FIND_CLASS_METHOD.invoke(child, name);
                    if (clazz != null) {
                        if (resolve) {
                            resolveClass(clazz);
                        }
                        return clazz;
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            // 5.
            if (!delegate) {
                try {
                    clazz = Class.forName(name, false, getParent());
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
            return null;
        }
    }

    //
    @Override
    public synchronized URL getResource(String name) {
        synchronized (getLockObject(name)) {
            // 0. find in local cache
            ResourceEntry resource = loadedResources.get(name);
            if (resource != null) {
                return resource.getUrl();
            }

            // 1. load by j2se
            URL url = j2seClassLoader.getResource(name);
            if (url != null) {
                return url;
            }

            // 2. parent delegate
            if (delegate) {
                url = getParent().getResource(name);
                if (url != null) {
                    return url;
                }
            }

            // 3.
            ResourceEntry resourceEntry = findResourceInternal(name, name);
            if (resourceEntry != null) {
                return resourceEntry.getUrl();
            }

            // 4.
            if (currentJar == null && child != null) {
                try {
                    url = (URL) FIND_RESOURCE_METHOD.invoke(child, name);
                    if (url != null) {
                        return url;
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            // 5.
            if (delegate) {
                url = getParent().getResource(name);
                if (url != null) {
                    return url;
                }
            }
            return null;
        }
    }

    @Override
    public synchronized InputStream getResourceAsStream(String name) {
        synchronized (getLockObject(name)) {
            // 0. find in local cache
            ResourceEntry resource = loadedResources.get(name);
            if (resource != null) {
                return new ByteArrayInputStream(resource.getBytes());
            }

            // 1. load by j2se
            InputStream inputStream = j2seClassLoader.getResourceAsStream(name);
            if (inputStream != null) {
                return inputStream;
            }

            // 2. parent delegate
            if (delegate) {
                inputStream = getParent().getResourceAsStream(name);
                if (inputStream != null) {
                    return inputStream;
                }
            }

            // 3.
            ResourceEntry resourceEntry = findResourceInternal(name, name);
            if (resourceEntry != null) {
                return new ByteArrayInputStream(resource.getBytes());
            }

            // 4.
            if (currentJar == null && child != null) {
                try {
                    URL url = (URL) FIND_RESOURCE_METHOD.invoke(child, name);
                    if (url != null) {
                        inputStream = url.openStream();
                        if (inputStream != null) {
                            return inputStream;
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            // 5.
            if (delegate) {
                inputStream = getParent().getResourceAsStream(name);
                if (inputStream != null) {
                    return inputStream;
                }
            }
            return null;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return findClassInternal(name);
    }

    // find from local get from global
    @Override
    public synchronized URL findResource(String name) {
        synchronized (getLockObject(name)) {
            ResourceEntry resource = findResourceInternal(name, name);
            return resource.getUrl();
        }
    }

    protected boolean containsClass(String name) {
        String path = name.replace('.', '/') + CLASSS_SUBFIX;
        if (currentJar != null && currentJar.getJarEntry(path) != null) {
            return true;
        } else {
            return false;
        }
    }

    protected synchronized Class<?> findClassInternal(String name) throws ClassNotFoundException {
        ResourceEntry resource = loadedResources.get(name);
        if (resource != null) {
            return resource.getClazz();
        }
        String path = name.replace('.', '/') + CLASSS_SUBFIX;
        resource = findResourceInternal(name, path);
        if (resource == null) {
            return null;
        } else {
            String packageName = null;
            int pos = name.lastIndexOf('.');
            if (pos >= 0) {
                packageName = name.substring(0, pos);
                if (securityManager != null) {
                    try {
                        securityManager.checkPackageAccess(packageName);
                    } catch (SecurityException se) {
                        String error = "Security Violation, attempt to use Restricted Class: " + name;
                        throw new ClassNotFoundException(error, se);
                    }
                }
            }
            Package pkg = null;
            if (packageName != null) {
                pkg = getPackage(packageName);
                if (pkg == null) {
                    try {
                        if (resource.getManifest() == null) {
                            definePackage(packageName, null, null, null, null, null, null, null);
                        } else {
                            definePackage(packageName, resource.getManifest(), resource.getUrl());
                        }
                    } catch (IllegalArgumentException e) {
                    }
                    pkg = getPackage(packageName);
                }
            }

            Class clazz = defineClass(name, resource.getBytes(), 0, resource.getBytes().length,
                                      new CodeSource(fatJarURL, resource.getCertificates()));
            resource.setClazz(clazz);
            return clazz;
        }
    }

    protected ResourceEntry findResourceInternal(String name, String path) {
        if (notFoundResources.contains(name)) {
            return null;
        }
        if (this.currentJar != null) {
            ResourceEntry resource = findResourceInternal0(this.currentJar, name, path);
            if (resource != null) {
                return resource;
            }
        }
        if (this.dependencyJars != null) {
            for (JarFile item : this.dependencyJars) {
                ResourceEntry resource = findResourceInternal0(item, name, path);
                if (resource != null) {
                    return resource;
                }
            }
        }
        notFoundResources.add(name);
        return null;
    }

    protected ResourceEntry findResourceInternal0(JarFile jarFile, String name, String path) {
        JarEntry jarEntry = jarFile.getJarEntry(path);
        if (jarEntry == null) {
            return null;
        } else {
            ResourceEntry resource = new ResourceEntry();
            InputStream inputStream = null;
            try {
                inputStream = jarFile.getInputStream(jarEntry);
                int conentLength = (int) jarEntry.getSize();
                byte[] bytes = new byte[(int) conentLength];
                int pos = 0;
                while (true) {
                    int next = inputStream.read(bytes, pos, bytes.length - pos);
                    if (next <= next) {
                        break;
                    }
                    pos += next;
                }
                resource.setBytes(bytes);
                resource.setManifest(jarFile.getManifest());
                resource.setCertificates(jarEntry.getCertificates());
                resource.setUrl(new URL(JAR_PROTOCOL + filePathPrefix + SEPARATOR + path));
            } catch (IOException e) {
                // ignore
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
            loadedResources.put(name, resource);
            return resource;
        }
    }

    protected ClassLoader getChild() {
        return child;
    }

    private Object getLockObject(String className) {
        Object newLock = new Object();
        Object lock = lockMap.putIfAbsent(className, newLock);
        if (lock == null) {
            return newLock;
        } else {
            return lock;
        }
    }

    private class ResourceEntry {

        private byte[]       bytes;
        private URL          url;
        private Class<?>     clazz;
        private Manifest     manifest;
        public Certificate[] certificates;

        public byte[] getBytes() {
            return bytes;
        }

        public void setBytes(byte[] bytes) {
            this.bytes = bytes;
        }

        public URL getUrl() {
            return url;
        }

        public void setUrl(URL url) {
            this.url = url;
        }

        public Class<?> getClazz() {
            return clazz;
        }

        public void setClazz(Class<?> clazz) {
            this.clazz = clazz;
        }

        public Manifest getManifest() {
            return manifest;
        }

        public void setManifest(Manifest manifest) {
            this.manifest = manifest;
        }

        public Certificate[] getCertificates() {
            return certificates;
        }

        public void setCertificates(Certificate[] certificates) {
            this.certificates = certificates;
        }
    }
}
