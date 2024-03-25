package io.jenkins.plugins;

import hudson.Extension;
import hudson.util.Secret;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

@Extension
public class XguardianGlobalConfiguration extends GlobalConfiguration {

    private static final XguardianGlobalConfiguration INSTANCE = new XguardianGlobalConfiguration();

    private Secret token;
    private String email;
    private String password;

    public XguardianGlobalConfiguration() {
        load();
    }

    public static XguardianGlobalConfiguration get() {
        return INSTANCE;
    }

    public Secret getToken() {
        return token;
    }

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }

    public void setToken(Secret token) {
        this.token = token;
        save();
    }

    public void setEmail(String email) {
        this.email = email;
        save();
    }

    public void setPassword(String password) {
        this.password = password;
        save();
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        req.bindJSON(this, json);
        save();
        return true;
    }
}
