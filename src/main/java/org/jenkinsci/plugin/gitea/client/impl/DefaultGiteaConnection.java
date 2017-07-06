/*
 * The MIT License
 *
 * Copyright (c) 2017, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugin.gitea.client.impl;

import com.damnhandy.uri.template.UriTemplate;
import com.damnhandy.uri.template.UriTemplateBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.ISO8601DateFormat;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugin.gitea.client.api.GiteaAuth;
import org.jenkinsci.plugin.gitea.client.api.GiteaAuthToken;
import org.jenkinsci.plugin.gitea.client.api.GiteaAuthUser;
import org.jenkinsci.plugin.gitea.client.api.GiteaBranch;
import org.jenkinsci.plugin.gitea.client.api.GiteaCommitStatus;
import org.jenkinsci.plugin.gitea.client.api.GiteaConnection;
import org.jenkinsci.plugin.gitea.client.api.GiteaHook;
import org.jenkinsci.plugin.gitea.client.api.GiteaIssue;
import org.jenkinsci.plugin.gitea.client.api.GiteaIssueState;
import org.jenkinsci.plugin.gitea.client.api.GiteaOrganization;
import org.jenkinsci.plugin.gitea.client.api.GiteaOwner;
import org.jenkinsci.plugin.gitea.client.api.GiteaPullRequest;
import org.jenkinsci.plugin.gitea.client.api.GiteaRepository;
import org.jenkinsci.plugin.gitea.client.api.GiteaUser;
import org.jenkinsci.plugin.gitea.client.api.GiteaVersion;

/**
 * Default implementation of {@link GiteaConnection} that uses the JVM native {@link URLConnection} to communicate
 * with a remote Gitea server. Requires a valid end-point. Package protected to ensure access goes through the
 * API and not direct construction.
 */
class DefaultGiteaConnection implements GiteaConnection {

    private final String serverUrl;

    private final GiteaAuth authentication;
    private ObjectMapper mapper = new ObjectMapper();

    DefaultGiteaConnection(@NonNull String serverUrl,
                           @NonNull GiteaAuth authentication) {
        this.serverUrl = serverUrl;
        this.authentication = authentication;
    }

    /**
     * Workaround for a bug in {@code HttpURLConnection.setRequestMethod(String)}
     * The implementation of Sun/Oracle is throwing a {@code ProtocolException}
     * when the method is other than the HTTP/1.1 default methods. So to use {@code PROPFIND}
     * and others, we must apply this workaround.
     */
    private static void setRequestMethodViaJreBugWorkaround(final HttpURLConnection httpURLConnection,
                                                            final String method) {
        try {
            httpURLConnection.setRequestMethod(method); // Check whether we are running on a buggy JRE
        } catch (final ProtocolException pe) {
            try {
                AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
                    @Override
                    public Object run() throws NoSuchFieldException, IllegalAccessException {
                        final Object target;
                        if (httpURLConnection instanceof HttpsURLConnection) {
                            final Field delegate = httpURLConnection.getClass().getDeclaredField("delegate");
                            delegate.setAccessible(true);
                            target = delegate.get(httpURLConnection);
                        } else {
                            target = httpURLConnection;
                        }
                        final Field methodField = HttpURLConnection.class.getDeclaredField("method");
                        methodField.setAccessible(true);
                        methodField.set(target, method);
                        return null;
                    }
                });
            } catch (final PrivilegedActionException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                } else {
                    throw new RuntimeException(cause);
                }
            }
        }
    }

    @Override
    public GiteaVersion fetchVersion() throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/version")
                        .build(),
                GiteaVersion.class
        );
    }

    @Override
    public GiteaUser fetchCurrentUser() throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/user")
                        .build(),
                GiteaUser.class
        );
    }

    @Override
    public GiteaUser fetchUser(String name) throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/users")
                        .path(UriTemplateBuilder.var("name"))
                        .build()
                        .set("name", name),
                GiteaUser.class
        );
    }

    @Override
    public GiteaOrganization fetchOrganization(String name) throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/orgs")
                        .path(UriTemplateBuilder.var("name"))
                        .build()
                        .set("name", name),
                GiteaOrganization.class
        );
    }

    @Override
    public GiteaRepository fetchRepository(String username, String name) throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .build()
                        .set("username", username)
                        .set("name", name),
                GiteaRepository.class
        );
    }

    @Override
    public GiteaRepository fetchRepository(GiteaOwner owner, String name) throws IOException, InterruptedException {
        return fetchRepository(owner.getUsername(), name);
    }

    @Override
    public List<GiteaRepository> fetchCurrentUserRepositories() throws IOException, InterruptedException {
        return getList(
                api()
                        .literal("/user")
                        .literal("/repos")
                        .build(),
                GiteaRepository.class
        );
    }

    @Override
    public List<GiteaRepository> fetchRepositories(String username) throws IOException, InterruptedException {
        return getList(
                api()
                        .literal("/users")
                        .path(UriTemplateBuilder.var("username"))
                        .literal("/repos")
                        .build()
                        .set("username", username),
                GiteaRepository.class
        );
    }

    @Override
    public List<GiteaRepository> fetchRepositories(GiteaOwner owner) throws IOException, InterruptedException {
        return fetchRepositories(owner.getUsername());
    }

    @Override
    public GiteaBranch fetchBranch(String username, String repository, String name)
            throws IOException, InterruptedException {
        if (name.indexOf('/') != -1) {
            // TODO remove hack once https://github.com/go-gitea/gitea/issues/2088 is fixed
            for (GiteaBranch b : fetchBranches(username, repository)) {
                if (name.equals(b.getName())) {
                    return b;
                }
            }
        }
        return getObject(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("repository"))
                        .literal("/branches")
                        .path(UriTemplateBuilder.var("name", true))
                        .build()
                        .set("username", username)
                        .set("repository", repository)
                        .set("name", StringUtils.split(name, '/')),
                GiteaBranch.class
        );
    }

    @Override
    public GiteaBranch fetchBranch(GiteaRepository repository, String name) throws IOException, InterruptedException {
        if (name.indexOf('/') != -1) {
            // TODO remove hack once https://github.com/go-gitea/gitea/issues/2088 is fixed
            for (GiteaBranch b : fetchBranches(repository)) {
                if (name.equals(b.getName())) {
                    return b;
                }
            }
        }
        return getObject(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("repository"))
                        .literal("/branches")
                        .path(UriTemplateBuilder.var("name", true))
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("repository", repository.getName())
                        .set("name", StringUtils.split(name, '/')),
                GiteaBranch.class
        );
    }

    @Override
    public List<GiteaBranch> fetchBranches(String username, String name) throws IOException, InterruptedException {
        return getList(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/branches")
                        .build()
                        .set("username", username)
                        .set("name", name),
                GiteaBranch.class
        );
    }

    @Override
    public List<GiteaBranch> fetchBranches(GiteaRepository repository) throws IOException, InterruptedException {
        return getList(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/branches")
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName()),
                GiteaBranch.class
        );
    }

    @Override
    public List<GiteaUser> fetchCollaborators(String username, String name) throws IOException, InterruptedException {
        return getList(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/collaborators")
                        .build()
                        .set("username", username)
                        .set("name", name),
                GiteaUser.class
        );
    }

    @Override
    public List<GiteaUser> fetchCollaborators(GiteaRepository repository) throws IOException, InterruptedException {
        return getList(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/collaborators")
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName()),
                GiteaUser.class
        );
    }

    @Override
    public boolean checkCollaborator(String username, String name, String collaboratorName)
            throws IOException, InterruptedException {
        return status(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/collaborators")
                        .path(UriTemplateBuilder.var("collaboratorName"))
                        .build()
                        .set("username", username)
                        .set("name", name)
                        .set("collaboratorName", collaboratorName)
        ) / 100 == 2;
    }

    @Override
    public boolean checkCollaborator(GiteaRepository repository, String collaboratorName)
            throws IOException, InterruptedException {
        return status(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/collaborators")
                        .path(UriTemplateBuilder.var("collaboratorName"))
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName())
                        .set("collaboratorName", collaboratorName)
        ) / 100 == 2;
    }

    @Override
    public List<GiteaHook> fetchHooks(String organizationName) throws IOException, InterruptedException {
        return getList(
                api()
                        .literal("/orgs")
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/hooks")
                        .build()
                        .set("name", organizationName),
                GiteaHook.class
        );
    }

    @Override
    public List<GiteaHook> fetchHooks(GiteaOrganization organization) throws IOException, InterruptedException {
        return getList(
                api()
                        .literal("/orgs")
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/hooks")
                        .build()
                        .set("name", organization.getUsername()),
                GiteaHook.class
        );
    }

    @Override
    public GiteaHook createHook(GiteaOrganization organization, GiteaHook hook)
            throws IOException, InterruptedException {
        return post(api()
                        .literal("/orgs")
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/hooks")
                        .build()
                        .set("name", organization.getUsername()),
                hook, GiteaHook.class);
    }

    @Override
    public void deleteHook(GiteaOrganization organization, GiteaHook hook) throws IOException, InterruptedException {
        deleteHook(organization, hook.getId());
    }

    @Override
    public void deleteHook(GiteaOrganization organization, long id) throws IOException, InterruptedException {
        int status = delete(api()
                .literal("/orgs")
                .path(UriTemplateBuilder.var("name"))
                .literal("/hooks")
                .path(UriTemplateBuilder.var("id"))
                .build()
                .set("name", organization.getUsername())
                .set("id", id)
        );
        if (status / 100 != 2) {
            throw new IOException(
                    "Could not delete organization hook " + id + " for " + organization.getUsername() + " HTTP/"
                            + status);
        }
    }

    @Override
    public void updateHook(GiteaOrganization organization, GiteaHook hook) throws IOException, InterruptedException {
        GiteaHook diff = new GiteaHook();
        diff.setConfig(hook.getConfig());
        diff.setActive(hook.isActive());
        diff.setEvents(hook.getEvents());
        patch(api()
                        .literal("/orgs")
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/hooks")
                        .path(UriTemplateBuilder.var("id"))
                        .build()
                        .set("name", organization.getUsername())
                        .set("id", hook.getId()),
                diff, Void.class);
    }

    @Override
    public List<GiteaHook> fetchHooks(String username, String name) throws IOException, InterruptedException {
        return getList(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/hooks")
                        .build()
                        .set("username", username)
                        .set("name", name),
                GiteaHook.class
        );
    }

    @Override
    public List<GiteaHook> fetchHooks(GiteaRepository repository) throws IOException, InterruptedException {
        return getList(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/hooks")
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName()),
                GiteaHook.class
        );
    }

    @Override
    public GiteaHook createHook(GiteaRepository repository, GiteaHook hook) throws IOException, InterruptedException {
        return post(api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/hooks")
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName()),
                hook, GiteaHook.class);
    }

    @Override
    public void deleteHook(GiteaRepository repository, GiteaHook hook) throws IOException, InterruptedException {
        deleteHook(repository, hook.getId());
    }

    @Override
    public void deleteHook(GiteaRepository repository, long id) throws IOException, InterruptedException {
        int status = delete(api()
                .literal("/repos")
                .path(UriTemplateBuilder.var("username"))
                .path(UriTemplateBuilder.var("name"))
                .literal("/hooks")
                .path(UriTemplateBuilder.var("id"))
                .build()
                .set("username", repository.getOwner().getUsername())
                .set("name", repository.getName())
                .set("id", id)
        );
        if (status / 100 != 2) {
            throw new IOException(
                    "Could not delete hook " + id + " for " + repository.getOwner().getUsername() + "/" + repository
                            .getName() + " HTTP/" + status);
        }
    }

    @Override
    public void updateHook(GiteaRepository repository, GiteaHook hook) throws IOException, InterruptedException {
        GiteaHook diff = new GiteaHook();
        diff.setConfig(hook.getConfig());
        diff.setActive(hook.isActive());
        diff.setEvents(hook.getEvents());
        patch(api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/hooks")
                        .path(UriTemplateBuilder.var("id"))
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName())
                        .set("id", hook.getId()),
                diff, Void.class);
    }

    @Override
    public List<GiteaCommitStatus> fetchCommitStatuses(GiteaRepository repository, String sha)
            throws IOException, InterruptedException {
        return getList(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/statuses")
                        .path(UriTemplateBuilder.var("sha"))
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName())
                        .set("sha", sha),
                GiteaCommitStatus.class
        );
    }

    @Override
    public GiteaCommitStatus createCommitStatus(String username, String repository, String sha,
                                                GiteaCommitStatus status) throws IOException, InterruptedException {
        return post(api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/statuses")
                        .path(UriTemplateBuilder.var("sha"))
                        .build()
                        .set("username", username)
                        .set("name", repository)
                        .set("sha", sha),
                status, GiteaCommitStatus.class);
    }

    @Override
    public GiteaCommitStatus createCommitStatus(GiteaRepository repository, String sha, GiteaCommitStatus status)
            throws IOException, InterruptedException {
        return post(api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/statuses")
                        .path(UriTemplateBuilder.var("sha"))
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName())
                        .set("sha", sha),
                status, GiteaCommitStatus.class);
    }

    @Override
    public GiteaPullRequest fetchPullRequest(String username, String name, long id)
            throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/pulls")
                        .path(UriTemplateBuilder.var("id"))
                        .build()
                        .set("username", username)
                        .set("name", name)
                        .set("id", Long.toString(id)),
                GiteaPullRequest.class
        );
    }

    @Override
    public GiteaPullRequest fetchPullRequest(GiteaRepository repository, long id)
            throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/pulls")
                        .path(UriTemplateBuilder.var("id"))
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName())
                        .set("id", Long.toString(id)),
                GiteaPullRequest.class
        );
    }

    @Override
    public List<GiteaPullRequest> fetchPullRequests(String username, String name)
            throws IOException, InterruptedException {
        return fetchPullRequests(username, name, EnumSet.of(GiteaIssueState.OPEN));
    }

    @Override
    public List<GiteaPullRequest> fetchPullRequests(GiteaRepository repository)
            throws IOException, InterruptedException {
        return fetchPullRequests(repository, EnumSet.of(GiteaIssueState.OPEN));
    }

    @Override
    public List<GiteaPullRequest> fetchPullRequests(String username, String name, Set<GiteaIssueState> states)
            throws IOException, InterruptedException {
        String state = null;
        if (states != null && states.size() == 1) {
            // state query only works if there is one state
            for (GiteaIssueState s : GiteaIssueState.values()) {
                if (states.contains(s)) {
                    state = s.getKey();
                }
            }
        }
        return getList(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/pulls")
                        .query(UriTemplateBuilder.var("state"))
                        .build()
                        .set("username", username)
                        .set("name", name)
                        .set("state", state),
                GiteaPullRequest.class
        );
    }

    @Override
    public List<GiteaPullRequest> fetchPullRequests(GiteaRepository repository, Set<GiteaIssueState> states)
            throws IOException, InterruptedException {
        String state = null;
        if (states != null && states.size() == 1) {
            // state query only works if there is one state
            for (GiteaIssueState s : GiteaIssueState.values()) {
                if (states.contains(s)) {
                    state = s.getKey();
                }
            }
        }
        return getList(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/pulls")
                        .query(UriTemplateBuilder.var("state"))
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName())
                        .set("state", state),
                GiteaPullRequest.class
        );
    }

    @Override
    public List<GiteaIssue> fetchIssues(String username, String name)
            throws IOException, InterruptedException {
        return fetchIssues(username, name, EnumSet.of(GiteaIssueState.OPEN));
    }

    @Override
    public List<GiteaIssue> fetchIssues(GiteaRepository repository)
            throws IOException, InterruptedException {
        return fetchIssues(repository, EnumSet.of(GiteaIssueState.OPEN));
    }

    @Override
    public List<GiteaIssue> fetchIssues(String username, String name, Set<GiteaIssueState> states)
            throws IOException, InterruptedException {
        String state = null;
        if (states != null && states.size() == 1) {
            // state query only works if there is one state
            for (GiteaIssueState s : GiteaIssueState.values()) {
                if (states.contains(s)) {
                    state = s.getKey();
                }
            }
        }
        return getList(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/issues")
                        .query(UriTemplateBuilder.var("state"))
                        .build()
                        .set("username", username)
                        .set("name", name)
                        .set("state", state),
                GiteaIssue.class
        );
    }

    @Override
    public List<GiteaIssue> fetchIssues(GiteaRepository repository, Set<GiteaIssueState> states)
            throws IOException, InterruptedException {
        String state = null;
        if (states != null && states.size() == 1) {
            // state query only works if there is one state
            for (GiteaIssueState s : GiteaIssueState.values()) {
                if (states.contains(s)) {
                    state = s.getKey();
                }
            }
        }
        return getList(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/issues")
                        .query(UriTemplateBuilder.var("state"))
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName())
                        .set("state", state),
                GiteaIssue.class
        );
    }

    @Override
    public byte[] fetchFile(GiteaRepository repository, String ref, String path)
            throws IOException, InterruptedException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/raw")
                        .path(UriTemplateBuilder.var("ref", true))
                        .path(UriTemplateBuilder.var("path", true))
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName())
                        .set("ref", StringUtils.split(ref, '/'))
                        .set("path", StringUtils.split(path, "/"))
                        .expand()
        ).openConnection();
        withAuthentication(connection);
        try {
            connection.connect();
            int status = connection.getResponseCode();
            if (status == 404) {
                throw new FileNotFoundException(path);
            }
            if (status / 100 == 2) {
                try (InputStream is = connection.getInputStream()) {
                    return IOUtils.toByteArray(is);
                }
            }
            throw new IOException("HTTP " + status + "/" + connection.getResponseMessage());
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public boolean checkFile(GiteaRepository repository, String ref, String path)
            throws IOException, InterruptedException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/raw")
                        .path(UriTemplateBuilder.var("ref", true))
                        .path(UriTemplateBuilder.var("path", true))
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName())
                        .set("ref", StringUtils.split(ref, '/'))
                        .set("path", StringUtils.split(path, "/"))
                        .expand()
        ).openConnection();
        withAuthentication(connection);
        try {
            connection.connect();
            int status = connection.getResponseCode();
            if (status == 404) {
                return false;
            }
            if (status / 100 == 2) {
                return true;
            }
            throw new IOException("HTTP " + status + "/" + connection.getResponseMessage());
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public void close() throws IOException {
    }

    private UriTemplateBuilder api() {
        return UriTemplate.buildFromTemplate(serverUrl).literal("/api/v1");
    }

    private void withAuthentication(HttpURLConnection connection) {
        if (authentication instanceof GiteaAuthUser) {
            String auth = (((GiteaAuthUser) authentication).getUsername()) + ":" + (((GiteaAuthUser) authentication).
                    getPassword());
            connection.setRequestProperty("Authorization", "Basic " + Base64.encodeBase64String(auth.getBytes(
                    StandardCharsets.UTF_8)));
        } else if (authentication instanceof GiteaAuthToken) {
            connection.setRequestProperty("Authorization", "token " + ((GiteaAuthToken) authentication).getToken());
        }
    }

    private int status(UriTemplate template) throws IOException, InterruptedException {
        HttpURLConnection connection = (HttpURLConnection) new URL(template.expand()).openConnection();
        withAuthentication(connection);
        try {
            connection.connect();
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private int delete(UriTemplate template) throws IOException, InterruptedException {
        HttpURLConnection connection = (HttpURLConnection) new URL(template.expand()).openConnection();
        withAuthentication(connection);
        connection.setRequestMethod("DELETE");
        try {
            connection.connect();
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private <T> T getObject(UriTemplate template, final Class<T> modelClass) throws IOException, InterruptedException {
        HttpURLConnection connection = (HttpURLConnection) new URL(template.expand()).openConnection();
        withAuthentication(connection);
        try {
            connection.connect();
            int status = connection.getResponseCode();
            if (status == 200) {
                try (InputStream is = connection.getInputStream()) {
                    return mapper.readerFor(modelClass).readValue(is);
                }
            }
            throw new IOException("HTTP " + status + "/" + connection.getResponseMessage());
        } finally {
            connection.disconnect();
        }
    }

    private <T> T post(UriTemplate template, Object body, final Class<T> modelClass)
            throws IOException, InterruptedException {
        HttpURLConnection connection = (HttpURLConnection) new URL(template.expand()).openConnection();
        withAuthentication(connection);
        connection.setRequestMethod("POST");
        byte[] bytes;
        if (body != null) {
            bytes = mapper.writer(new ISO8601DateFormat()).writeValueAsBytes(body);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Content-Length", Integer.toString(bytes.length));
            connection.setDoOutput(true);
        } else {
            bytes = null;
            connection.setDoOutput(false);
        }
        connection.setDoInput(!Void.class.equals(modelClass));

        try {
            connection.connect();
            if (bytes != null) {
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            if (status / 100 == 2) {
                if (Void.class.equals(modelClass)) {
                    return null;
                }
                try (InputStream is = connection.getInputStream()) {
                    return mapper.readerFor(modelClass).readValue(is);
                }
            }
            throw new IOException(
                    "HTTP " + status + "/" + connection.getResponseMessage() + "\n" + (bytes != null ? new String(bytes,
                            StandardCharsets.UTF_8) : ""));
        } finally {
            connection.disconnect();
        }
    }

    private <T> T patch(UriTemplate template, Object body, final Class<T> modelClass)
            throws IOException, InterruptedException {
        HttpURLConnection connection = (HttpURLConnection) new URL(template.expand()).openConnection();
        withAuthentication(connection);
        setRequestMethodViaJreBugWorkaround(connection, "PATCH");
        byte[] bytes;
        if (body != null) {
            bytes = mapper.writer(new ISO8601DateFormat()).writeValueAsBytes(body);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Content-Length", Integer.toString(bytes.length));
            connection.setDoOutput(true);
        } else {
            bytes = null;
            connection.setDoOutput(false);
        }
        connection.setDoInput(true);

        try {
            connection.connect();
            if (bytes != null) {
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            if (status / 100 == 2) {
                if (Void.class.equals(modelClass)) {
                    return null;
                }
                try (InputStream is = connection.getInputStream()) {
                    return mapper.readerFor(modelClass).readValue(is);
                }
            }
            throw new IOException("HTTP " + status + "/" + connection.getResponseMessage());
        } finally {
            connection.disconnect();
        }
    }

    private <T> List<T> getList(UriTemplate template, final Class<T> modelClass)
            throws IOException, InterruptedException {
        HttpURLConnection connection = (HttpURLConnection) new URL(template.expand()).openConnection();
        withAuthentication(connection);
        try {
            connection.connect();
            int status = connection.getResponseCode();
            if (status / 100 == 2) {
                try (InputStream is = connection.getInputStream()) {
                    return mapper.readerFor(mapper.getTypeFactory().constructCollectionType(List.class, modelClass))
                            .readValue(is);
                }
            }
            throw new IOException("HTTP " + status + "/" + connection.getResponseMessage());
        } finally {
            connection.disconnect();
        }
    }
}