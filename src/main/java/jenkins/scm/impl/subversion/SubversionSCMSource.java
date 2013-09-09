/*
 * The MIT License
 *
 * Copyright (c) 2013, CloudBees, Inc., Stephen Connolly.
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
package jenkins.scm.impl.subversion;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCertificateCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.scm.SubversionRepositoryStatus;
import hudson.scm.SubversionSCM;
import hudson.security.ACL;
import hudson.util.IOException2;
import hudson.util.ListBoxModel;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.SCMSourceOwners;
import net.jcip.annotations.GuardedBy;
import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOCase;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * A {@link SCMSource} for Subversion.
 *
 * @author Stephen Connolly
 */
public class SubversionSCMSource extends SCMSource {

    private static final String DEFAULT_INCLUDES = "trunk,branches/*,tags/*,sandbox/*";

    private static final String DEFAULT_EXCLUDES = "";

    public static final StringListComparator COMPARATOR = new StringListComparator();

    public static final Logger LOGGER = Logger.getLogger(SubversionSCMSource.class.getName());

    private final String remoteBase;

    private final String credentialsId;

    private final String includes;

    private final String excludes;

    @GuardedBy("this")
    private transient String uuid;

    @DataBoundConstructor
    @SuppressWarnings("unused") // by stapler
    public SubversionSCMSource(String id, String remoteBase, String credentialsId, String includes, String excludes) {
        super(id);
        this.remoteBase = StringUtils.removeEnd(remoteBase, "/") + "/";
        this.credentialsId = credentialsId;
        this.includes = StringUtils.defaultIfEmpty(includes, DEFAULT_INCLUDES);
        this.excludes = StringUtils.defaultIfEmpty(excludes, DEFAULT_EXCLUDES);
    }

    /**
     * Gets the credentials id.
     *
     * @return the credentials id.
     */
    @SuppressWarnings("unused") // by stapler
    public String getCredentialsId() {
        return credentialsId;
    }

    /**
     * Gets the comma separated list of exclusions.
     *
     * @return the comma separated list of exclusions.
     */
    @SuppressWarnings("unused") // by stapler
    public String getExcludes() {
        return excludes;
    }

    /**
     * Gets the comma separated list of inclusions.
     *
     * @return the comma separated list of inclusions.
     */
    @SuppressWarnings("unused") // by stapler
    public String getIncludes() {
        return includes;
    }

    /**
     * Gets the base SVN URL of the project.
     *
     * @return the base SVN URL of the project.
     */
    @SuppressWarnings("unused") // by stapler
    public String getRemoteBase() {
        return remoteBase;
    }

    public synchronized String getUuid() {
        if (uuid == null) {
            SVNRepositoryView repository = null;
            try {
                SVNURL repoURL = SVNURL.parseURIEncoded(remoteBase);
                repository = openSession(repoURL);
                uuid = repository.getUuid();
            } catch (SVNException e) {
                LOGGER.log(Level.WARNING, "Could not connect to remote repository " + remoteBase + " to determine UUID",
                        e);
            } finally {
                closeSession(repository);
            }

        }
        return uuid;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public <O extends SCMHeadObserver> O fetch(@NonNull final O observer,
                                               @CheckForNull TaskListener listener)
            throws IOException {
        listener = defaultListener(listener);
        SVNRepositoryView repository = null;
        try {
            listener.getLogger().println("Opening conection to " + remoteBase);
            SVNURL repoURL = SVNURL.parseURIEncoded(remoteBase);
            repository = openSession(repoURL);

            String repoPath = SubversionSCM.DescriptorImpl.getRelativePath(repoURL, repository.getRepository());
            List<String> prefix = Collections.emptyList();
            fetch(listener,
                    repository,
                    -1,
                    repoPath,
                    toPaths(splitCludes(includes)),
                    prefix,
                    prefix,
                    toPaths(splitCludes(excludes)), getCriteria(), observer
            );
        } catch (SVNException e) {
            e.printStackTrace(listener.error("Could not communicate with Subversion server"));
            throw new IOException2(e.getMessage(), e);
        } finally {
            closeSession(repository);
        }
        return observer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SCMRevision fetch(@NonNull SCMHead head, @CheckForNull TaskListener listener)
            throws IOException {
        listener = defaultListener(listener);
        SVNRepositoryView repository = null;
        try {
            listener.getLogger().println("Opening conection to " + remoteBase);
            SVNURL repoURL = SVNURL.parseURIEncoded(remoteBase);
            repository = openSession(repoURL);
            String repoPath = SubversionSCM.DescriptorImpl.getRelativePath(repoURL, repository.getRepository());
            String path = SVNPathUtil.append(repoPath, head.getName());
            SVNRepositoryView.NodeEntry svnEntry = repository.getNode(path, -1);
            return new SCMRevisionImpl(head, svnEntry.getRevision());
        } catch (SVNException e) {
            throw new IOException2(e.getMessage(), e);
        } finally {
            closeSession(repository);
        }
    }

    private static void closeSession(@CheckForNull SVNRepositoryView repository) {
        if (repository != null) {
            repository.close();
        }
    }

    private SVNRepositoryView openSession(SVNURL repoURL) throws SVNException {
        return new SVNRepositoryView(repoURL, credentialsId == null ? null : CredentialsMatchers
                .firstOrNull(CredentialsProvider.lookupCredentials(StandardCredentials.class, getOwner(),
                        ACL.SYSTEM, URIRequirementBuilder.fromUri(repoURL.toString()).build()),
                        CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialsId),
                                CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardCredentials.class),
                                        CredentialsMatchers.instanceOf(SSHUserPrivateKey.class)))));
    }

    void fetch(@NonNull TaskListener listener,
               @NonNull final SVNRepositoryView repository,
               long rev,
               @NonNull final String repoPath,
               @NonNull SortedSet<List<String>> paths,
               @NonNull List<String> prefix,
               @NonNull List<String> realPath,
               @NonNull SortedSet<List<String>> excludedPaths,
               @CheckForNull SCMSourceCriteria branchCriteria,
               @NonNull SCMHeadObserver observer)
            throws IOException, SVNException {
        String svnPath = SVNPathUtil.append(repoPath, StringUtils.join(realPath, '/'));
        assert prefix.size() == realPath.size();
        assert wildcardStartsWith(realPath, prefix);
        SortedMap<List<String>, SortedSet<List<String>>> includePaths = groupPaths(paths, prefix);
        listener.getLogger().println("Checking directory " + svnPath + (rev > -1 ? "@" + rev : "@HEAD"));
        SVNRepositoryView.NodeEntry node = repository.getNode(svnPath, rev);
        if (!SVNNodeKind.DIR.equals(node.getType()) || node.getChildren() == null) {
            return;
        }
        for (Map.Entry<List<String>, SortedSet<List<String>>> entry : includePaths.entrySet()) {
            for (List<String> path : entry.getValue()) {
                String name = path.get(prefix.size());
                SVNRepositoryView.ChildEntry[] children = node.getChildren().clone();
                Arrays.sort(children, new Comparator<SVNRepositoryView.ChildEntry>() {
                    public int compare(SVNRepositoryView.ChildEntry o1, SVNRepositoryView.ChildEntry o2) {
                        long diff = o2.getRevision() - o1.getRevision();
                        return diff < 0 ? -1 : diff > 0 ? 1 : 0;
                    }
                });
                for (final SVNRepositoryView.ChildEntry svnEntry : children) {
                    if (svnEntry.getType() == SVNNodeKind.DIR && isMatch(svnEntry.getName(), name)) {
                        List<String> childPrefix = copyAndAppend(prefix, name);
                        List<String> childRealPath = copyAndAppend(realPath, svnEntry.getName());
                        if (wildcardStartsWith(childRealPath, excludedPaths)) {
                            continue;
                        }
                        if (path.equals(childPrefix)) {
                            final String childPath = StringUtils.join(childRealPath, '/');
                            final String candidateRootPath = SVNPathUtil.append(repoPath, childPath);
                            final long candidateRevision = svnEntry.getRevision();
                            final long lastModified = svnEntry.getLastModified();
                            listener.getLogger().println(
                                    "Checking candidate branch " + candidateRootPath + "@" + candidateRevision);
                            if (branchCriteria == null || branchCriteria.isHead(
                                    new SCMSourceCriteria.Probe() {
                                        @Override
                                        public String name() {
                                            return childPath;
                                        }

                                        @Override
                                        public long lastModified() {
                                            return lastModified;
                                        }

                                        @Override
                                        public boolean exists(@NonNull String path) throws IOException {
                                            try {
                                                return repository.checkPath(
                                                        SVNPathUtil.append(candidateRootPath, path),
                                                        candidateRevision) != SVNNodeKind.NONE;
                                            } catch (SVNException e) {
                                                throw new IOException2(e.getMessage(), e);
                                            }
                                        }
                                    }, listener)) {
                                listener.getLogger().println("Met criteria");
                                SCMHead head = new SCMHead(childPath);
                                observer.observe(head, new SCMRevisionImpl(head, svnEntry.getRevision()));
                                if (!observer.isObserving()) {
                                    return;
                                }
                            } else {
                                listener.getLogger().println("Does not meet criteria");
                            }
                        } else {
                            fetch(listener, repository, svnEntry.getRevision(), repoPath, paths,
                                    childPrefix,
                                    childRealPath, excludedPaths, branchCriteria, observer);
                        }
                    }
                }
            }
        }
    }

    /**
     * Copies a list and appends some more values.
     *
     * @param list   the list.
     * @param values the values to append.
     * @param <T>    the type of values to append.
     * @return the list.
     */
    @NonNull
    private static <T> List<T> copyAndAppend(@NonNull List<T> list, T... values) {
        List<T> childPrefix = new ArrayList<T>(list.size() + values.length);
        childPrefix.addAll(list);
        childPrefix.addAll(Arrays.asList(values));
        return childPrefix;
    }

    /**
     * Groups a set of path segments based on a supplied prefix.
     *
     * @param pathSegments the input path segments.
     * @param prefix       the prefix to group on.
     * @return a map, all keys will {@link #startsWith(java.util.List, java.util.List)} the input prefix and be longer
     *         than the input prefix, all values will {@link #startsWith(java.util.List,
     *         java.util.List)} their corresponding key.
     */
    @NonNull
    static SortedMap<List<String>, SortedSet<List<String>>> groupPaths(@NonNull SortedSet<List<String>> pathSegments,
                                                                       @NonNull List<String> prefix) {
        // ensure pre-condition is valid and ensure we are using a copy
        pathSegments = filterPaths(pathSegments, prefix);

        SortedMap<List<String>, SortedSet<List<String>>> result =
                new TreeMap<List<String>, SortedSet<List<String>>>(COMPARATOR);
        while (!pathSegments.isEmpty()) {
            List<String> longestPrefix = null;
            int longestIndex = -1;
            for (List<String> pathSegment : pathSegments) {
                if (longestPrefix == null) {
                    longestPrefix = pathSegment;
                    longestIndex = indexOfNextWildcard(pathSegment, prefix.size());

                } else {
                    int index = indexOfNextWildcard(pathSegment, prefix.size());
                    if (index > longestIndex) {
                        longestPrefix = pathSegment;
                        longestIndex = index;
                    }
                }
            }
            assert longestPrefix != null;
            longestPrefix = new ArrayList<String>(longestPrefix.subList(0, longestIndex));
            SortedSet<List<String>> group = filterPaths(pathSegments, longestPrefix);
            result.put(longestPrefix, group);
            pathSegments.removeAll(group);
        }
        String optimization;
        while (null != (optimization = getOptimizationPoint(result.keySet(), prefix.size()))) {
            List<String> optimizedPrefix = copyAndAppend(prefix, optimization);
            SortedSet<List<String>> optimizedGroup = new TreeSet<List<String>>(COMPARATOR);
            for (Iterator<Map.Entry<List<String>, SortedSet<List<String>>>> iterator = result.entrySet().iterator();
                 iterator.hasNext(); ) {
                Map.Entry<List<String>, SortedSet<List<String>>> entry = iterator.next();
                if (startsWith(entry.getKey(), optimizedPrefix)) {
                    iterator.remove();
                    optimizedGroup.addAll(entry.getValue());
                }
            }
            result.put(optimizedPrefix, optimizedGroup);
        }
        return result;
    }

    /**
     * Returns the string that has multiple matches and can therefore be used to optimize the set of prefixes.
     *
     * @param newPrefixes   the proposed set of prefixes.
     * @param oldPrefixSize the length of the old prefix.
     * @return either a string that when appended to the old prefix will give a prefix with multiple matches in the
     *         supplied set of new prefixes or {@code null} if there is no such string.
     */
    @CheckForNull
    static String getOptimizationPoint(@NonNull Set<List<String>> newPrefixes, int oldPrefixSize) {
        Set<String> set = new HashSet<String>();
        for (List<String> p : newPrefixes) {
            if (p.size() <= oldPrefixSize) {
                continue;
            }
            String value = p.get(oldPrefixSize);
            if (set.contains(value)) {
                return value;
            }
            set.add(value);
        }
        return null;
    }

    /**
     * Returns the index of the next wildcard segment on or after the specified start index.
     *
     * @param pathSegment the path segments.
     * @param startIndex  the first index to check.
     * @return the index with a wildcard or {@code -1} if none exist.
     */
    static int indexOfNextWildcard(@NonNull List<String> pathSegment, int startIndex) {
        int index = startIndex;
        ListIterator<String> i = pathSegment.listIterator(index);
        while (i.hasNext()) {
            String segment = i.next();
            if (segment.indexOf('*') != -1 || segment.indexOf('?') != -1) {
                break;
            }
            index++;
        }
        return index;
    }

    /**
     * Returns {@code true} if and only if the value starts with the supplied prefix.
     *
     * @param value  the value.
     * @param prefix the candidate prefix.
     * @return {@code true} if and only if the value starts with the supplied prefix.
     */
    static boolean startsWith(@NonNull List<String> value, @NonNull List<String> prefix) {
        if (value.size() < prefix.size()) {
            return false;
        }
        ListIterator<String> i1 = value.listIterator();
        ListIterator<String> i2 = prefix.listIterator();
        while (i1.hasNext() && i2.hasNext()) {
            if (!i1.next().equals(i2.next())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if and only if the value starts with the supplied prefix.
     *
     * @param value          the value.
     * @param wildcardPrefix the candidate prefix.
     * @return {@code true} if and only if the value starts with the supplied prefix.
     */
    static boolean wildcardStartsWith(@NonNull List<String> value, @NonNull List<String> wildcardPrefix) {
        if (value.size() < wildcardPrefix.size()) {
            return false;
        }
        ListIterator<String> i1 = value.listIterator();
        ListIterator<String> i2 = wildcardPrefix.listIterator();
        while (i1.hasNext() && i2.hasNext()) {
            if (!isMatch(i1.next(), i2.next())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if and only if the value starts with any of the supplied prefixes.
     *
     * @param value            the value.
     * @param wildcardPrefixes the candidate prefixes.
     * @return {@code true} if and only if the value starts with any of the supplied prefixes.
     */
    static boolean wildcardStartsWith(@NonNull List<String> value, @NonNull Collection<List<String>> wildcardPrefixes) {
        for (List<String> wildcardPrefix : wildcardPrefixes) {
            if (wildcardStartsWith(value, wildcardPrefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filters the set of path segments, retaining only those that start with the supplied prefix.
     *
     * @param pathSegments the set of path segments.
     * @param prefix       the prefix.
     * @return a new set of path segments.
     */
    @NonNull
    static SortedSet<List<String>> filterPaths(@NonNull SortedSet<List<String>> pathSegments,
                                               @NonNull List<String> prefix) {
        SortedSet<List<String>> result = new TreeSet<List<String>>(COMPARATOR);
        for (List<String> pathSegment : pathSegments) {
            if (startsWith(pathSegment, prefix)) {
                result.add(pathSegment);
            }
        }
        return result;
    }

    /**
     * Splits a set of path strings into a set of lists of path segments.
     *
     * @param pathStrings the set of path strings.
     * @return the set of lists of path segments.
     */
    @NonNull
    static SortedSet<List<String>> toPaths(@NonNull SortedSet<String> pathStrings) {
        SortedSet<List<String>> result = new TreeSet<List<String>>(COMPARATOR);
        for (String clude : pathStrings) {
            result.add(Arrays.asList(clude.split("/")));
        }
        return result;
    }

    /**
     * Split a comma separated set of includes/excludes into a set of strings.
     *
     * @param cludes a comma separated set of includes/excludes.
     * @return a set of strings.
     */
    @NonNull
    static SortedSet<String> splitCludes(@CheckForNull String cludes) {
        TreeSet<String> result = new TreeSet<String>();
        StringTokenizer tokenizer = new StringTokenizer(StringUtils.defaultString(cludes), ",");
        while (tokenizer.hasMoreTokens()) {
            String clude = tokenizer.nextToken().trim();
            if (StringUtils.isNotEmpty(clude)) {
                result.add(clude.trim());
            }
        }
        return result;
    }

    /**
     * Checks if we have a match against a wildcard matcher.
     *
     * @param value           the value
     * @param wildcareMatcher the wildcard matcher
     * @return {@code true} if and only if the value matches the wildcard matcher.
     */
    static boolean isMatch(@NonNull String value, @NonNull String wildcareMatcher) {
        return FilenameUtils.wildcardMatch(value, wildcareMatcher, IOCase.SENSITIVE);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public SubversionSCM build(@NonNull SCMHead head,
                               @CheckForNull SCMRevision revision) {
        if (revision != null && !head.equals(revision.getHead())) {
            revision = null;
        }
        if (revision != null && !(revision instanceof SCMRevisionImpl)) {
            revision = null;
        }
        StringBuilder remote = new StringBuilder(remoteBase);
        if (!remoteBase.endsWith("/")) {
            remote.append('/');
        }
        remote.append(head.getName());
        if (revision != null) {
            remote.append('@').append(((SCMRevisionImpl) revision).getRevision());
        } else if (remote.indexOf("@") != -1) {
            // name contains an @ so need to ensure there is an @ at the end of the name
            remote.append('@');
        }
        return new SubversionSCM(remote.toString(), credentialsId, ".");
    }

    /**
     * Our implementation.
     */
    public static class SCMRevisionImpl extends SCMRevision {

        /**
         * The subversion revision.
         */
        private long revision;

        public SCMRevisionImpl(SCMHead head, long revision) {
            super(head);
            this.revision = revision;
        }

        public long getRevision() {
            return revision;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            SCMRevisionImpl that = (SCMRevisionImpl) o;

            return revision == that.revision && getHead().equals(that.getHead());

        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return (int) (revision ^ (revision >>> 32));
        }
    }

    static class StringListComparator implements Comparator<List<String>> {
        public int compare(List<String> o1, List<String> o2) {
            ListIterator<String> e1 = o1.listIterator();
            ListIterator<String> e2 = o2.listIterator();
            while (e1.hasNext() && e2.hasNext()) {
                String s1 = e1.next();
                String s2 = e2.next();
                int rv = s1.compareTo(s2);
                if (rv != 0) {
                    return rv;
                }
            }
            if (e1.hasNext()) {
                return -1;
            }
            if (e2.hasNext()) {
                return 1;
            }
            return 0;
        }
    }

    @Extension
    @SuppressWarnings("unused") // by jenkins
    public static class DescriptorImpl extends SCMSourceDescriptor {
        static final Pattern URL_PATTERN = Pattern.compile("(https?|svn(\\+[a-z0-9]+)?|file)://.+");

        /**
         * {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return Messages.SubversionSCMSource_DisplayName();
        }

        /**
         * Stapler helper method.
         *
         * @param context    the context.
         * @param remoteBase the remote base.
         * @return list box model.
         */
        @SuppressWarnings("unused") // by stapler
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath SCMSourceOwner context,
                                                     @QueryParameter String remoteBase) {
            List<DomainRequirement> domainRequirements;
            domainRequirements = URIRequirementBuilder.fromUri(remoteBase.trim()).build();
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(
                            CredentialsMatchers.anyOf(
                                    CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                    CredentialsMatchers.instanceOf(StandardCertificateCredentials.class),
                                    CredentialsMatchers.instanceOf(SSHUserPrivateKey.class)
                            ),
                            CredentialsProvider.lookupCredentials(StandardCredentials.class,
                                    context,
                                    ACL.SYSTEM,
                                    domainRequirements)
                    );
        }

    }

    /**
     * We need to listen out for post-commit hooks
     */
    @Extension
    @SuppressWarnings("unused") // instantiated by Jenkins
    public static class ListenerImpl extends SubversionRepositoryStatus.Listener {

        /**
         * Maximum number of repositories to retain... since we should only ever have 1-2 relevant, this size
         * shouldn't matter much, but keep it finite to prevent memory stealing.
         */
        public static final int RECENT_SIZE = 64;

        /**
         * Guard against repeated calls by poorly configured hook scripts.
         */
        @GuardedBy("itself")
        private final Map<String, Long> recentUpdates = new LinkedHashMap<String, Long>(RECENT_SIZE) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() >= RECENT_SIZE;
            }
        };

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean onNotify(UUID uuid, long revision, Set<String> paths) {
            final String id = uuid.toString();
            synchronized (recentUpdates) {
                Long recentUpdate = recentUpdates.get(id);
                if (recentUpdate != null && revision == recentUpdate) {
                    LOGGER.log(Level.FINE, "Received duplicate post-commit hook from {0} for revision {1} on paths {2}",
                            new Object[]{uuid, revision, paths});
                    return false;
                }
                recentUpdates.put(id, revision);
            }
            LOGGER.log(Level.INFO, "Received post-commit hook from {0} for revision {1} on paths {2}",
                    new Object[]{uuid, revision, paths});
            boolean notified = false;
            // run in high privilege to see all the projects anonymous users don't see.
            // this is safe because when we actually schedule a build, it's a build that can
            // happen at some random time anyway.
            Authentication old = SecurityContextHolder.getContext().getAuthentication();
            SecurityContextHolder.getContext().setAuthentication(ACL.SYSTEM);
            try {
                for (SCMSourceOwner owner : SCMSourceOwners.all()) {
                    for (SCMSource source : owner.getSCMSources()) {
                        if (source instanceof SubversionSCMSource) {
                            if (id.equals(((SubversionSCMSource) source).getUuid())) {
                                LOGGER.log(Level.INFO, "SCM changes detected relevant to {0}. Notifying update",
                                        owner.getFullDisplayName());
                                owner.onSCMSourceUpdated(source);
                                notified = true;
                            }
                        }
                    }
                }
            } finally {
                SecurityContextHolder.getContext().setAuthentication(old);
            }
            if (!notified) {
                LOGGER.log(Level.INFO, "No subversion consumers for UUID {0}", uuid);
            }
            return notified;
        }
    }

}
