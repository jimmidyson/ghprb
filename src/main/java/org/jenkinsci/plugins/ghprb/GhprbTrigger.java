package org.jenkinsci.plugins.ghprb;

import antlr.ANTLRException;

import com.coravy.hudson.plugins.github.GithubProjectProperty;
import com.google.common.annotations.VisibleForTesting;

import hudson.Extension;
import hudson.Util;
import hudson.model.*;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.git.util.BuildData;
import hudson.triggers.TriggerDescriptor;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.ghprb.extensions.GhprbExtension;
import org.jenkinsci.plugins.ghprb.extensions.GhprbExtensionDescriptor;
import org.jenkinsci.plugins.ghprb.extensions.comments.GhprbBuildLog;
import org.jenkinsci.plugins.ghprb.extensions.comments.GhprbBuildResultMessage;
import org.jenkinsci.plugins.ghprb.extensions.comments.GhprbBuildStatus;
import org.jenkinsci.plugins.ghprb.extensions.comments.GhprbPublishJenkinsUrl;
import org.kohsuke.github.GHAuthorization;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GitHub;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public class GhprbTrigger extends GhprbTriggerBackwardsCompatible {

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    private static final Logger logger = Logger.getLogger(GhprbTrigger.class.getName());
    private final String adminlist;
    private final Boolean allowMembersOfWhitelistedOrgsAsAdmin;
    private final String orgslist;
    private final String cron;
    private final String triggerPhrase;
    private final Boolean onlyTriggerPhrase;
    private final Boolean useGitHubHooks;
    private final Boolean permitAll;
    private String whitelist;
    private Boolean autoCloseFailedPullRequests;
    private Boolean displayBuildErrorsOnDownstreamBuilds;
    private List<GhprbBranch> whiteListTargetBranches;
    private String commitStatusContext;
    private transient Ghprb helper;
    private String project;
    

    private DescribableList<GhprbExtension, GhprbExtensionDescriptor> extensions;
    
    public DescribableList<GhprbExtension, GhprbExtensionDescriptor> getExtensions() {
        if (extensions == null) {
            extensions = new DescribableList<GhprbExtension, GhprbExtensionDescriptor>(Saveable.NOOP,Util.fixNull(extensions));
        }
        return extensions;
    }
    
    private void setExtensions(List<GhprbExtension> extensions) {
        this.extensions = new DescribableList<GhprbExtension, GhprbExtensionDescriptor>(
                Saveable.NOOP,Util.fixNull(extensions));
    }

    @DataBoundConstructor
    public GhprbTrigger(String adminlist, 
            String whitelist, 
            String orgslist, 
            String cron, 
            String triggerPhrase, 
            Boolean onlyTriggerPhrase, 
            Boolean useGitHubHooks, 
            Boolean permitAll,
            Boolean autoCloseFailedPullRequests, 
            Boolean displayBuildErrorsOnDownstreamBuilds, 
            String commentFilePath, 
            List<GhprbBranch> whiteListTargetBranches,
            Boolean allowMembersOfWhitelistedOrgsAsAdmin, 
            String msgSuccess, 
            String msgFailure, 
            String commitStatusContext,
            List<GhprbExtension> extensions
            ) throws ANTLRException {
        super(cron);
        this.adminlist = adminlist;
        this.whitelist = whitelist;
        this.orgslist = orgslist;
        this.cron = cron;
        this.triggerPhrase = triggerPhrase;
        this.onlyTriggerPhrase = onlyTriggerPhrase;
        this.useGitHubHooks = useGitHubHooks;
        this.permitAll = permitAll;
        this.autoCloseFailedPullRequests = autoCloseFailedPullRequests;
        this.displayBuildErrorsOnDownstreamBuilds = displayBuildErrorsOnDownstreamBuilds;
        this.whiteListTargetBranches = whiteListTargetBranches;
        this.commitStatusContext = commitStatusContext;
        this.allowMembersOfWhitelistedOrgsAsAdmin = allowMembersOfWhitelistedOrgsAsAdmin;
        setExtensions(extensions);
    }

    @Override
    public Object readResolve() {
        convertPropertiesToExtensions();
        return this;
    }
    
    public static DescriptorImpl getDscp() {
        return DESCRIPTOR;
    }

    @Override
    public void start(AbstractProject<?, ?> project, boolean newInstance) {
        this.project = project.getFullName();
        
        if (project.isDisabled()) {
            logger.log(Level.FINE, "Project is disabled, not starting trigger for job " + this.project);
            return;
        }
        if (project.getProperty(GithubProjectProperty.class) == null) {
            logger.log(Level.INFO, "GitHub project not set up, cannot start ghprb trigger for job " + this.project);
            return;
        }
        try {
            helper = createGhprb(project);
        } catch (IllegalStateException ex) {
            logger.log(Level.SEVERE, "Can't start ghprb trigger", ex);
            return;
        }

        logger.log(Level.INFO, "Starting the ghprb trigger for the {0} job; newInstance is {1}", 
                new String[] { this.project, String.valueOf(newInstance) });
        super.start(project, newInstance);
        helper.init();
    }

    Ghprb createGhprb(AbstractProject<?, ?> project) {
        return new Ghprb(project, this, getDescriptor().getPullRequests(project.getFullName()));
    }

    @Override
    public void stop() {
        logger.log(Level.INFO, "Stopping the ghprb trigger for project {0}", this.project);
        if (helper != null) {
            helper.stop();
            helper = null;
        }
        super.stop();
    }

    @Override
    public void run() {
        // triggers are always triggered on the cron, but we just no-op if we are using GitHub hooks.
        if (getUseGitHubHooks()) {
            return;
        }

        if (helper == null) {
            logger.log(Level.SEVERE, "Helper is null, unable to run trigger");
            return;
        }

        if (helper.isProjectDisabled()) {
            logger.log(Level.FINE, "Project is disabled, ignoring trigger run call");
            return;
        }
        
        logger.log(Level.FINE, "Running trigger for {0}", project);
        
        helper.run();
        getDescriptor().save();
    }

    public QueueTaskFuture<?> startJob(GhprbCause cause, GhprbRepository repo) {
        ArrayList<ParameterValue> values = getDefaultParameters();
        final String commitSha = cause.isMerged() ? "origin/pr/" + cause.getPullID() + "/merge" : cause.getCommit();
        values.add(new StringParameterValue("sha1", commitSha));
        values.add(new StringParameterValue("ghprbActualCommit", cause.getCommit()));
        String triggerAuthor = "";
        String triggerAuthorEmail = "";

        try {
            triggerAuthor = getString(cause.getTriggerSender().getName(), "");
        } catch (Exception e) {}
        try {
            triggerAuthorEmail = getString(cause.getTriggerSender().getEmail(), "");
        } catch (Exception e) {}

        setCommitAuthor(cause, values);

        values.add(new StringParameterValue("ghprbTriggerAuthor", triggerAuthor));
        values.add(new StringParameterValue("ghprbTriggerAuthorEmail", triggerAuthorEmail));
        final StringParameterValue pullIdPv = new StringParameterValue("ghprbPullId", String.valueOf(cause.getPullID()));
        values.add(pullIdPv);
        values.add(new StringParameterValue("ghprbTargetBranch", String.valueOf(cause.getTargetBranch())));
        values.add(new StringParameterValue("ghprbSourceBranch", String.valueOf(cause.getSourceBranch())));
        values.add(new StringParameterValue("GIT_BRANCH", String.valueOf(cause.getSourceBranch())));
        // it's possible the GHUser doesn't have an associated email address
        values.add(new StringParameterValue("ghprbPullAuthorEmail", getString(cause.getAuthorEmail(), "")));
        values.add(new StringParameterValue("ghprbPullDescription", String.valueOf(cause.getShortDescription())));
        values.add(new StringParameterValue("ghprbPullTitle", String.valueOf(cause.getTitle())));
        values.add(new StringParameterValue("ghprbPullLink", String.valueOf(cause.getUrl())));

        // add the previous pr BuildData as an action so that the correct change log is generated by the GitSCM plugin
        // note that this will be removed from the Actions list after the job is completed so that the old (and incorrect)
        // one isn't there
        return this.job.scheduleBuild2(job.getQuietPeriod(), cause, new ParametersAction(values), findPreviousBuildForPullId(pullIdPv));
    }

    private void setCommitAuthor(GhprbCause cause, ArrayList<ParameterValue> values) {
        String authorName = "";
        String authorEmail = "";
        if (cause.getCommitAuthor() != null) {
            authorName = getString(cause.getCommitAuthor().getName(), "");
            authorEmail = getString(cause.getCommitAuthor().getEmail(), "");
        }

        values.add(new StringParameterValue("ghprbActualCommitAuthor", authorName));
        values.add(new StringParameterValue("ghprbActualCommitAuthorEmail", authorEmail));
    }

    private String getString(String actual, String d) {
        return actual == null ? d : actual;
    }

    /**
     * Find the previous BuildData for the given pull request number; this may return null
     */
    private BuildData findPreviousBuildForPullId(StringParameterValue pullIdPv) {
        // find the previous build for this particular pull request, it may not be the last build
        for (Run<?, ?> r : job.getBuilds()) {
            ParametersAction pa = r.getAction(ParametersAction.class);
            if (pa != null) {
                for (ParameterValue pv : pa.getParameters()) {
                    if (pv.equals(pullIdPv)) {
                        for (BuildData bd : r.getActions(BuildData.class)) {
                            return bd;
                        }
                    }
                }
            }
        }
        return null;
    }

    private ArrayList<ParameterValue> getDefaultParameters() {
        ArrayList<ParameterValue> values = new ArrayList<ParameterValue>();
        ParametersDefinitionProperty pdp = this.job.getProperty(ParametersDefinitionProperty.class);
        if (pdp != null) {
            for (ParameterDefinition pd : pdp.getParameterDefinitions()) {
                if (pd.getName().equals("sha1"))
                    continue;
                values.add(pd.getDefaultParameterValue());
            }
        }
        return values;
    }

    public void addWhitelist(String author) {
        whitelist = whitelist + " " + author;
        try {
            this.job.save();
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Failed to save new whitelist", ex);
        }
    }

    public String getAdminlist() {
        if (adminlist == null) {
            return "";
        }
        return adminlist;
    }

    public Boolean getAllowMembersOfWhitelistedOrgsAsAdmin() {
        return allowMembersOfWhitelistedOrgsAsAdmin != null && allowMembersOfWhitelistedOrgsAsAdmin;
    }

    public String getWhitelist() {
        if (whitelist == null) {
            return "";
        }
        return whitelist;
    }

    public String getOrgslist() {
        if (orgslist == null) {
            return "";
        }
        return orgslist;
    }

    public String getCron() {
        return cron;
    }

    public String getTriggerPhrase() {
        if (triggerPhrase == null) {
            return "";
        }
        return triggerPhrase;
    }

    public Boolean getOnlyTriggerPhrase() {
        return onlyTriggerPhrase != null && onlyTriggerPhrase;
    }

    public Boolean getUseGitHubHooks() {
        return useGitHubHooks != null && useGitHubHooks;
    }

    public Boolean getPermitAll() {
        return permitAll != null && permitAll;
    }

    public Boolean isAutoCloseFailedPullRequests() {
        if (autoCloseFailedPullRequests == null) {
            Boolean autoClose = getDescriptor().getAutoCloseFailedPullRequests();
            return (autoClose != null && autoClose);
        }
        return autoCloseFailedPullRequests;
    }

    public Boolean isDisplayBuildErrorsOnDownstreamBuilds() {
        if (displayBuildErrorsOnDownstreamBuilds == null) {
            Boolean displayErrors = getDescriptor().getDisplayBuildErrorsOnDownstreamBuilds();
            return (displayErrors != null && displayErrors);
        }
        return displayBuildErrorsOnDownstreamBuilds;
    }

    public List<GhprbBranch> getWhiteListTargetBranches() {
        if (whiteListTargetBranches == null) {
            return new ArrayList<GhprbBranch>();
        }
        return whiteListTargetBranches;
    }

    public String getCommitStatusContext() {
        return commitStatusContext;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @VisibleForTesting
    void setHelper(Ghprb helper) {
        this.helper = helper;
    }

    public GhprbBuilds getBuilds() {
        if (helper == null) {
            logger.log(Level.SEVERE, "The ghprb trigger for {0} wasn''t properly started - helper is null", this.project);
            return null;
        }
        return helper.getBuilds();
    }

    public GhprbRepository getRepository() {
        if (helper == null) {
            logger.log(Level.SEVERE, "The ghprb trigger for {0} wasn''t properly started - helper is null", this.project);
            return null;
        }
        return helper.getRepository();
    }

    public static final class DescriptorImpl extends TriggerDescriptor {
        // GitHub username may only contain alphanumeric characters or dashes and cannot begin with a dash
        private static final Pattern adminlistPattern = Pattern.compile("((\\p{Alnum}[\\p{Alnum}-]*)|\\s)*");

        /**
         * These settings only really affect testing. When Jenkins calls configure() then the formdata will 
         * be used to replace all of these fields. Leaving them here is useful for
         * testing, but must not be confused with a default. They also should not be used as the default 
         * value in the global.jelly file as this value is dynamic and will not be
         * retained once configure() is called.
         */
        private String serverAPIUrl = "https://api.github.com";
        private String whitelistPhrase = ".*add\\W+to\\W+whitelist.*";
        private String okToTestPhrase = ".*ok\\W+to\\W+test.*";
        private String retestPhrase = ".*test\\W+this\\W+please.*";
        private String skipBuildPhrase = ".*\\[skip\\W+ci\\].*";
        private String cron = "H/5 * * * *";
        private Boolean useComments = false;
        private Boolean useDetailedComments = false;
        private GHCommitState unstableAs = GHCommitState.FAILURE;
        private List<GhprbBranch> whiteListTargetBranches;
        private String commitStatusContext = "";
        private Boolean autoCloseFailedPullRequests = false;
        private Boolean displayBuildErrorsOnDownstreamBuilds = false;

        private String username;
        private String password;
        private String accessToken;
        private String adminlist;
        
        
        private String requestForTestingPhrase;
        private transient GhprbGitHub gh;
        // map of jobs (by their fullName) and their map of pull requests
        private Map<String, ConcurrentMap<Integer, GhprbPullRequest>> jobs;
        
        public List<GhprbExtensionDescriptor> getExtensionDescriptors() {
            return GhprbExtensionDescriptor.allProject();
        }
        
        public List<GhprbExtensionDescriptor> getGlobalExtensionDescriptors() {
            return GhprbExtensionDescriptor.allGlobal();
        }
        
        private DescribableList<GhprbExtension, GhprbExtensionDescriptor> extensions;
        
        public DescribableList<GhprbExtension, GhprbExtensionDescriptor> getExtensions() {
            if (extensions == null) {
                extensions = new DescribableList<GhprbExtension, GhprbExtensionDescriptor>(Saveable.NOOP);
            }
            return extensions;
        }

        public DescriptorImpl() {
            load();
            readBackFromLegacy();
            if (jobs == null) {
                jobs = new HashMap<String, ConcurrentMap<Integer, GhprbPullRequest>>();
            }
        }

        @Override
        public boolean isApplicable(Item item) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "GitHub Pull Request Builder";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            serverAPIUrl = formData.getString("serverAPIUrl");
            username = formData.getString("username");
            password = formData.getString("password");
            accessToken = formData.getString("accessToken");
            adminlist = formData.getString("adminlist");
            requestForTestingPhrase = formData.getString("requestForTestingPhrase");
            whitelistPhrase = formData.getString("whitelistPhrase");
            okToTestPhrase = formData.getString("okToTestPhrase");
            retestPhrase = formData.getString("retestPhrase");
            skipBuildPhrase = formData.getString("skipBuildPhrase");
            cron = formData.getString("cron");
            useComments = formData.getBoolean("useComments");
            useDetailedComments = formData.getBoolean("useDetailedComments");
            unstableAs = GHCommitState.valueOf(formData.getString("unstableAs"));
            autoCloseFailedPullRequests = formData.getBoolean("autoCloseFailedPullRequests");
            displayBuildErrorsOnDownstreamBuilds = formData.getBoolean("displayBuildErrorsOnDownstreamBuilds");
            commitStatusContext = formData.getString("commitStatusContext");
            
            extensions = new DescribableList<GhprbExtension, GhprbExtensionDescriptor>(Saveable.NOOP);
            
            Object exts = formData.get("extensions");
            if (exts != null) {
                JSONArray extsArray;
                if (exts instanceof JSONArray) {
                    extsArray = formData.getJSONArray("extensions");
                    for (int i=0; i<extsArray.size(); ++i) {
                        JSONObject next = extsArray.getJSONObject(i);
                        extensions.add(createExtension(req, next));
                    }   
                } else {
                    extensions.add(createExtension(req, (JSONObject)exts));
                }
            }
            readBackFromLegacy();

            save();
            gh = new GhprbGitHub();
            return super.configure(req, formData);
        }
        
        private GhprbExtension createExtension(StaplerRequest req, JSONObject json) {
            String clazz = json.getString("stapler-class");
            if (StringUtils.isEmpty(clazz)) {
                return null;
            }
            Class<?> type;
            try {
                type = Class.forName(clazz);
                GhprbExtension extension = (GhprbExtension) req.bindJSON(type, json);
                return extension;
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
            return null;
        }

        public FormValidation doCheckAdminlist(@QueryParameter String value) throws ServletException {
            if (!adminlistPattern.matcher(value).matches()) {
                return FormValidation.error("GitHub username may only contain alphanumeric characters or dashes "
                        + "and cannot begin with a dash. Separate them with whitespaces.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckServerAPIUrl(@QueryParameter String value) {
            if ("https://api.github.com".equals(value)) {
                return FormValidation.ok();
            }
            if (value.endsWith("/api/v3") || value.endsWith("/api/v3/")) {
                return FormValidation.ok();
            }
            return FormValidation.warning("GitHub API URI is \"https://api.github.com\". GitHub Enterprise API URL ends with \"/api/v3\"");
        }
        
        public ListBoxModel doFillUnstableAsItems() {
            ListBoxModel items = new ListBoxModel();
            GHCommitState[] results = new GHCommitState[] {GHCommitState.SUCCESS,GHCommitState.ERROR,GHCommitState.FAILURE};
            for (GHCommitState nextResult : results) {
                String text = StringUtils.capitalize(nextResult.toString().toLowerCase());
                items.add(text, nextResult.toString());
                if (unstableAs.toString().equals(nextResult)) {
                    items.get(items.size()-1).selected = true;
                } 
            }

            return items;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public String getAdminlist() {
            return adminlist;
        }

        public String getRequestForTestingPhrase() {
            return requestForTestingPhrase;
        }

        public String getWhitelistPhrase() {
            return whitelistPhrase;
        }

        public String getOkToTestPhrase() {
            return okToTestPhrase;
        }

        public String getRetestPhrase() {
            return retestPhrase;
        }

        public String getSkipBuildPhrase() {
            return skipBuildPhrase;
        }

        public String getCron() {
            return cron;
        }

        public Boolean getUseComments() {
            return useComments;
        }

        public Boolean getUseDetailedComments() {
            return useDetailedComments;
        }


        public Boolean getAutoCloseFailedPullRequests() {
            return autoCloseFailedPullRequests;
        }

        public Boolean getDisplayBuildErrorsOnDownstreamBuilds() {
            return displayBuildErrorsOnDownstreamBuilds;
        }

        public String getServerAPIUrl() {
            return serverAPIUrl;
        }

        public GHCommitState getUnstableAs() {
            return unstableAs;
        }

        public boolean isUseComments() {
            return (useComments != null && useComments);
        }

        public boolean isUseDetailedComments() {
            return (useDetailedComments != null && useDetailedComments);
        }

        public String getCommitStatusContext() {
            return commitStatusContext;
        }

        public GhprbGitHub getGitHub() {
            if (gh == null) {
                gh = new GhprbGitHub();
            }
            return gh;
        }
        

        @VisibleForTesting
        void setGitHub(GhprbGitHub gh) {
            this.gh = gh;
        }

        public ConcurrentMap<Integer, GhprbPullRequest> getPullRequests(String projectName) {
            ConcurrentMap<Integer, GhprbPullRequest> ret;
            if (jobs.containsKey(projectName)) {
                Map<Integer, GhprbPullRequest> map = jobs.get(projectName);
                if (!(map instanceof ConcurrentMap)) {
                    map = new ConcurrentHashMap<Integer, GhprbPullRequest>(map);
                }
                jobs.put(projectName, (ConcurrentMap<Integer, GhprbPullRequest>) map);
                ret = (ConcurrentMap<Integer, GhprbPullRequest>) map;
            } else {
                ret = new ConcurrentHashMap<Integer, GhprbPullRequest>();
                jobs.put(projectName, ret);
            }
            return ret;
        }

        public FormValidation doCreateApiToken(@QueryParameter("username") final String username, 
                @QueryParameter("password") final String password) {
            try {
                GitHub gh = GitHub.connectToEnterprise(this.serverAPIUrl, username, password);
                GHAuthorization token = gh.createToken(Arrays.asList(GHAuthorization.REPO_STATUS, 
                        GHAuthorization.REPO), "Jenkins GitHub Pull Request Builder", null);
                return FormValidation.ok("Access token created: " + token.getToken());
            } catch (IOException ex) {
                return FormValidation.error("GitHub API token couldn't be created: " + ex.getMessage());
            }
        }

        public List<GhprbBranch> getWhiteListTargetBranches() {
            return whiteListTargetBranches;
        }
        

        @Deprecated
        private transient String publishedURL;
        @Deprecated
        private transient Integer logExcerptLines = 0;
        @Deprecated
        private transient String msgSuccess;
        @Deprecated
        private transient String msgFailure;
        
        public void readBackFromLegacy() {
            if (logExcerptLines != null && logExcerptLines > 0) {
                addIfMissing(new GhprbBuildLog(logExcerptLines));
                // logExceprtLines = null;
            }
            if (!StringUtils.isEmpty(publishedURL)) {
                addIfMissing(new GhprbPublishJenkinsUrl(publishedURL));
            }
            if (!StringUtils.isEmpty(msgFailure) || !StringUtils.isEmpty(msgSuccess)) {
                List<GhprbBuildResultMessage> messages = new ArrayList<GhprbBuildResultMessage>(2);
                if (!StringUtils.isEmpty(msgFailure)) {
                    messages.add(new GhprbBuildResultMessage(GHCommitState.FAILURE, msgFailure));
                    msgFailure = null;
                }
                if (!StringUtils.isEmpty(msgSuccess)) {
                    messages.add(new GhprbBuildResultMessage(GHCommitState.SUCCESS, msgSuccess));
                    msgSuccess = null;
                }
                addIfMissing(new GhprbBuildStatus(messages));
            }
        }
        

        private void addIfMissing(GhprbExtension ext) {
            if (getExtensions().get(ext.getClass()) == null) {
                getExtensions().add(ext);
            }
        }
        
    }
}
