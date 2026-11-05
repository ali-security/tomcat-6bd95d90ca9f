/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.servlets;

import java.io.File;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletContext;

import org.junit.Assert;
import org.junit.Test;

import static org.apache.catalina.startup.SimpleHttpClient.CRLF;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.session.FileStore;
import org.apache.catalina.session.PersistentManager;
import org.apache.catalina.startup.ExpandWar;
import org.apache.catalina.startup.SimpleHttpClient;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.tomcat.util.buf.ByteChunk;

/**
 * Tests for the lifecycle and naming of the temporary files used to process
 * partial PUT requests (CVE-2025-24813). The temporary file must not be named
 * after the target resource, must not be shared between different resources
 * and must not survive the request.
 */
public class TestDefaultServletPartialPutTempFile extends TomcatBaseTest {

    private File tempDocBase;


    @Override
    public void setUp() throws Exception {
        super.setUp();
        tempDocBase = Files.createTempDirectory(getTemporaryDirectory().toPath(), "put").toFile();
    }


    @Override
    public void tearDown() throws Exception {
        try {
            super.tearDown();
        } finally {
            ExpandWar.deleteDir(tempDocBase, false);
        }
    }


    /*
     * A partial PUT used to create a file in the work directory whose name was
     * derived from the request path. With file based session persistence that
     * allowed an attacker to plant a session file (e.g. ".evil.session") that
     * would then be deserialized when a request with the matching session ID
     * was received.
     */
    @Test
    public void testPartialPutDoesNotPlantSessionFile() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        Context ctxt = tomcat.addContext("", tempDocBase.getAbsolutePath());

        // File based session persistence uses the work directory by default
        PersistentManager manager = new PersistentManager();
        FileStore store = new FileStore();
        manager.setStore(store);
        ctxt.setManager(manager);

        configureDefaultServlet(ctxt);

        tomcat.start();
        ctxt.getResources().setCachingAllowed(false);

        File workDir = getWorkDir(ctxt);
        Set<String> filesBefore = listFiles(workDir);

        String payload = "SERIALIZED-PAYLOAD";
        Assert.assertTrue(doPartialPut("/evil.session", payload, 0, payload.length()).isResponse201());

        // The resource itself must have been written correctly
        Assert.assertEquals(payload, getResource("/evil.session"));

        // No file named after the resource path may appear in the work dir
        Assert.assertFalse(new File(workDir, ".evil.session").exists());
        // The session store must not see an attacker controlled session
        Assert.assertFalse(Arrays.asList(store.keys()).contains(".evil"));
        Assert.assertEquals(0, store.getSize());
        // No temporary file may be left behind
        Assert.assertEquals(filesBefore, listFiles(workDir));
    }


    /*
     * "/secret/data.txt" and "/secret.data.txt" used to map to the same
     * temporary file. Content left in that file by a partial PUT to one
     * resource must not leak into another resource.
     */
    @Test
    public void testPartialPutPathEquivalenceDoesNotLeakContent() throws Exception {
        startServer();

        Assert.assertTrue(new File(tempDocBase, "secret").mkdir());

        String secret = "TOP-SECRET-DATA";
        Assert.assertTrue(doPartialPut("/secret/data.txt", secret, 0, secret.length()).isResponse201());
        Assert.assertEquals(secret, getResource("/secret/data.txt"));

        // Equivalent path, only one byte provided for a resource of the same length
        Assert.assertTrue(doPartialPut("/secret.data.txt", "X", 0, secret.length()).isResponse201());

        String result = getResource("/secret.data.txt");
        Assert.assertFalse(result, result.contains("SECRET"));
        Assert.assertEquals("X" + new String(new char[secret.length() - 1]), result);
    }


    /*
     * Content placed in the temporary file by a partial PUT to one resource
     * must not be injected into a different resource that maps to the same
     * temporary file name.
     */
    @Test
    public void testPartialPutPathEquivalenceDoesNotInjectContent() throws Exception {
        startServer();

        Assert.assertTrue(new File(tempDocBase, "secret").mkdir());

        String injected = "INJECTED-BYTES!";
        Assert.assertTrue(doPartialPut("/secret.data.txt", injected, 0, injected.length()).isResponse201());

        Assert.assertTrue(doPartialPut("/secret/data.txt", "OK", 0, injected.length()).isResponse201());

        String result = getResource("/secret/data.txt");
        Assert.assertFalse(result, result.contains("JECTED"));
        Assert.assertEquals("OK" + new String(new char[injected.length() - 2]), result);
    }


    /*
     * The temporary file must be removed if processing of the partial PUT
     * fails, e.g. because the client aborts the request.
     */
    @Test
    public void testPartialPutTempFileDeletedOnClientAbort() throws Exception {
        Context ctxt = startServer();

        File workDir = getWorkDir(ctxt);
        Set<String> filesBefore = listFiles(workDir);

        try (Socket socket = new Socket("localhost", getPort())) {
            OutputStream os = socket.getOutputStream();
            String request = "PUT /abort.txt HTTP/1.1" + CRLF +
                    "Host: localhost:" + getPort() + CRLF +
                    "Content-Range: bytes=0-99/100" + CRLF +
                    "Content-Length: 100" + CRLF +
                    CRLF +
                    "Only part of the body";
            os.write(request.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Wait for the temporary file to be created
            int count = 0;
            while (listFiles(workDir).equals(filesBefore) && count < 100) {
                Thread.sleep(100);
                count++;
            }
            Assert.assertNotEquals("Temporary file was not created", filesBefore, listFiles(workDir));
        }

        // Client has aborted the request. The temporary file must be removed.
        int count = 0;
        while (!listFiles(workDir).equals(filesBefore) && count < 100) {
            Thread.sleep(100);
            count++;
        }
        Assert.assertEquals(filesBefore, listFiles(workDir));
        Assert.assertFalse(new File(tempDocBase, "abort.txt").exists());
    }


    private Context startServer() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        Context ctxt = tomcat.addContext("", tempDocBase.getAbsolutePath());
        configureDefaultServlet(ctxt);
        tomcat.start();
        // Disable caching
        ctxt.getResources().setCachingAllowed(false);
        return ctxt;
    }


    private static void configureDefaultServlet(Context ctxt) {
        Wrapper w = Tomcat.addServlet(ctxt, "default", DefaultServlet.class.getName());
        w.addInitParameter("readonly", "false");
        w.addInitParameter("allowPartialPut", "true");
        ctxt.addServletMappingDecoded("/", "default");
    }


    private static File getWorkDir(Context ctxt) {
        File workDir = (File) ctxt.getServletContext().getAttribute(ServletContext.TEMPDIR);
        Assert.assertNotNull(workDir);
        Assert.assertTrue(workDir.isDirectory());
        return workDir;
    }


    private static Set<String> listFiles(File dir) {
        Set<String> result = new HashSet<>();
        String[] names = dir.list();
        if (names != null) {
            result.addAll(Arrays.asList(names));
        }
        return result;
    }


    private PutClient doPartialPut(String path, String body, int start, int length) throws Exception {
        PutClient putClient = new PutClient(getPort());
        putClient.setRequest(new String[] {
                "PUT " + path + " HTTP/1.1" + CRLF +
                "Host: localhost:" + getPort() + CRLF +
                "Content-Range: bytes=" + start + "-" + (start + body.length() - 1) + "/" + length + CRLF +
                "Content-Length: " + body.length() + CRLF +
                "Connection: close" + CRLF +
                CRLF +
                body
        });
        putClient.connect();
        putClient.processRequest(false);
        putClient.disconnect();
        return putClient;
    }


    private String getResource(String path) throws Exception {
        ByteChunk responseBody = new ByteChunk();
        int rc = getUrl("http://localhost:" + getPort() + path, responseBody, null);
        Assert.assertEquals(200, rc);
        return responseBody.toString();
    }


    private static class PutClient extends SimpleHttpClient {

        public PutClient(int port) {
            setPort(port);
        }


        @Override
        public boolean isResponseBodyOK() {
            return false;
        }
    }
}
