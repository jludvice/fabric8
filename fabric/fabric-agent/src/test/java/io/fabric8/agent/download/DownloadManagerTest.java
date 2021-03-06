/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.agent.download;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.fabric8.maven.MavenResolver;
import io.fabric8.maven.MavenResolvers;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class DownloadManagerTest {

    public static Logger LOG = LoggerFactory.getLogger(DownloadManagerTest.class);

    private String karafHomeOld;
    private File karafHome;
    // local Maven repository - usually $FABRIC_HOME/system
    private String systemRepoUri;
    private File systemRepo;

    @Before
    public void init() throws Exception {
        karafHomeOld = System.getProperty("karaf.home");
        karafHome = new File("target/karaf");
        FileUtils.deleteDirectory(karafHome);

        karafHome.mkdirs();
        systemRepo = new File(karafHome, "system");
        systemRepo.mkdirs();
        systemRepoUri = systemRepo.getCanonicalFile().toURI().toString() + "@snapshots@id=karaf-default";
        System.setProperty("karaf.home", karafHome.getCanonicalPath());
    }

    @After
    public void cleanup() throws Exception {
        if (karafHomeOld != null) {
            System.setProperty("karaf.home", karafHomeOld);
        }
        FileUtils.deleteDirectory(karafHome);
    }

    @Test
    public void testDownloadUsingNonAuthenticatedProxy() throws Exception {
        Server server = new Server(0);
        server.setHandler(new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                response.setStatus(HttpServletResponse.SC_OK);
                baseRequest.setHandled(true);
                response.getOutputStream().write(new byte[] { 0x42 });
                response.getOutputStream().close();
            }
        });
        server.start();

        Properties custom = new Properties();
        custom.setProperty("org.ops4j.pax.url.mvn.proxySupport", "true");
        String settings = createMavenSettingsWithProxy(((ServerConnector)server.getConnectors()[0]).getLocalPort());
        DownloadManager dm = createDownloadManager("http://relevant.not/maven2@id=central", settings, custom);

        try {
            StreamProvider df = download(dm, "mvn:x.y/z/1.0");
            assertNotNull(df);
            assertNotNull(df.getUrl());
            assertNotNull(df.getFile());
            assertEquals("z-1.0.jar", df.getFile().getName());
            LOG.info("Downloaded URL={}, FILE={}", df.getUrl(), df.getFile());
        } finally {
            server.stop();
        }
    }

    @Test
    @Ignore("https://issues.apache.org/jira/browse/HTTPCLIENT-1652")
    public void testDownloadUsingAuthenticatedProxy() throws Exception {
        Server server = new Server(0);
        server.setHandler(new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                String proxyAuth = request.getHeader("Proxy-Authorization");
                if (proxyAuth == null || proxyAuth.trim().equals("")) {
                    response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
                    response.addHeader("Proxy-Authenticate", "Basic realm=\"Proxy Server\"");
                    baseRequest.setHandled(true);
                } else {
                    response.setStatus(HttpServletResponse.SC_OK);
                    baseRequest.setHandled(true);
                    response.getOutputStream().write(new byte[] { 0x42 });
                    response.getOutputStream().close();
                }
            }
        });
        server.start();

        Properties custom = new Properties();
        custom.setProperty("org.ops4j.pax.url.mvn.proxySupport", "true");
        String settings = createMavenSettingsWithProxy(((ServerConnector)server.getConnectors()[0]).getLocalPort());
        DownloadManager dm = createDownloadManager("http://relevant.not/maven2@id=central", settings, custom);

        try {
            StreamProvider df = download(dm, "mvn:x.y/z/1.0");
            assertNotNull(df);
            assertNotNull(df.getUrl());
            assertNotNull(df.getFile());
            assertEquals("z-1.0.jar", df.getFile().getName());
            LOG.info("Downloaded URL={}, FILE={}", df.getUrl(), df.getFile());
        } finally {
            server.stop();
        }
    }

    @Test
    public void testDownloadAlreadyDownloadedArtifact() throws Exception {
        DownloadManager dm = createDownloadManager("non-existing-settings.xml", null);
        File dir = new File(systemRepo, "x/y/z/1.0");
        dir.mkdirs();
        FileOutputStream artifact = new FileOutputStream(new File(dir, "z-1.0.jar"));
        artifact.write(new byte[] { 0x42 });
        artifact.close();

        StreamProvider df = download(dm, "mvn:x.y/z/1.0");
        assertNotNull(df);
        assertNotNull(df.getUrl());
        assertNotNull(df.getFile());
        assertEquals("z-1.0.jar", df.getFile().getName());
        LOG.info("Downloaded URL={}, FILE={}", df.getUrl(), df.getFile());
    }

    private StreamProvider download(DownloadManager manager, String url) throws Exception {
        final AtomicReference<StreamProvider> ref = new AtomicReference<>();
        Downloader downloader = manager.createDownloader();
        downloader.download(url, new DownloadCallback() {
            @Override
            public void downloaded(StreamProvider provider) throws Exception {
                synchronized (ref) {
                    ref.set(provider);
                    ref.notifyAll();
                }
            }
        });
        synchronized (ref) {
            ref.wait(30000);
            return ref.get();
        }
    }

    /**
     * Prepares DownloadManager to test
     *
     * @param remoteRepo
     * @param settingsFile
     * @param props
     * @return
     * @throws IOException
     */
    private DownloadManager createDownloadManager(String remoteRepo, String settingsFile, Properties props) throws IOException {
        File mavenSettings = new File(karafHome, settingsFile);
        Hashtable<String, String> properties = new Hashtable<>();
        if (props != null) {
            for (Map.Entry<Object, Object> entry : props.entrySet()) {
                properties.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        properties.put("org.ops4j.pax.url.mvn.localRepository", systemRepoUri);
        properties.put("org.ops4j.pax.url.mvn.repositories", remoteRepo);
        properties.put("org.ops4j.pax.url.mvn.defaultRepositories", systemRepoUri);
        properties.put("org.ops4j.pax.url.mvn.settings", mavenSettings.toURI().toString());

        MavenResolver resolver = MavenResolvers.createMavenResolver(properties, "org.ops4j.pax.url.mvn");
        return DownloadManagers.createDownloadManager(resolver, Executors.newSingleThreadScheduledExecutor());
    }

    /**
     * Prepares DownloadManager to test
     *
     * @param settingsFile
     * @param props
     * @return
     * @throws IOException
     */
    private DownloadManager createDownloadManager(String settingsFile, Properties props) throws IOException {
        return createDownloadManager("http://repo1.maven.org/maven2@id=central", settingsFile, props);
    }

    /**
     * Creates <code>settings.xml</code> file with http proxy using configured port
     *
     * @param localPort
     * @return
     */
    private String createMavenSettingsWithProxy(int localPort) throws IOException {
        InputStream settingsStream = getClass().getResourceAsStream("/maven-test-settings.xml");
        String settings = IOUtils.toString(settingsStream, "UTF-8");
        settings = settings.replace("<port>0</port>", "<port>" + localPort + "</port>");
        File settingsFile = new File(karafHome, "correct-settings.xml");
        FileOutputStream usedSettings = new FileOutputStream(settingsFile);
        IOUtils.write(settings, usedSettings);
        usedSettings.close();

        return "correct-settings.xml";
    }

}
