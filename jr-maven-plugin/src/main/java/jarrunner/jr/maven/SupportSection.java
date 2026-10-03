package jarrunner.jr.maven;

import datapotter.datahelper.Data;
import org.apache.maven.project.MavenProject;

/** PRP-31: who supports the app, shown in jr's error dialog (Email and Report issue buttons) and in -Xjr:doctor.
 *  Each value not set on the plugin comes from the pom: issueManagement/url, url, organization/name or the first
 *  developer's name, and the first developer's email. */
@Data
public final class SupportSection extends SupportSection_A {
    String name;
    String email;
    String issues;
    String url;

    /** The plugin's own settings, filled in from the pom; null when nothing is known at all. */
    static SupportSection of(MavenProject p, String name, String email, String issues, String url) {
        var dev = p == null || p.getDevelopers().isEmpty() ? null : p.getDevelopers().get(0);
        var s = new SupportSection()
                .name(first(name, p == null || p.getOrganization() == null ? null : p.getOrganization().getName(), dev == null ? null : dev.getName()))
                .email(first(email, dev == null ? null : dev.getEmail()))
                .issues(first(issues, p == null || p.getIssueManagement() == null ? null : p.getIssueManagement().getUrl()))
                .url(first(url, p == null ? null : p.getUrl()));
        return s.name() == null && s.email() == null && s.issues() == null && s.url() == null ? null : s;
    }

    private static String first(String... values) {
        for (var v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
