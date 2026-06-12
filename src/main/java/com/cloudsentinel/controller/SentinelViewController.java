package com.cloudsentinel.controller;

import java.util.Collections;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.cloudsentinel.service.AwsProfileService;
import com.cloudsentinel.service.AwsRegionService;
import com.cloudsentinel.service.DocsService;

/**
 * Spring MVC view controller serving all Thymeleaf-rendered pages for the Cloud Resource Sentinel UI.
 *
 * <p>Unlike the REST controllers that return JSON, this controller returns Thymeleaf template names
 * that are resolved to server-rendered HTML pages using Bootstrap 5. Each method populates the
 * Spring {@link Model} with the data needed by its template and sets the {@code currentPath}
 * attribute for navigation highlighting.</p>
 *
 * <p>Pages served:</p>
 * <ul>
 *   <li>{@code /} -- Landing page</li>
 *   <li>{@code /dashboard} -- Main dashboard with resource counts and cost overview</li>
 *   <li>{@code /dashboard/analyse} -- Scan submission form with profile and region selection</li>
 *   <li>{@code /dashboard/resources} -- Resource listing and filtering</li>
 *   <li>{@code /dashboard/summary} -- Scan summary view</li>
 *   <li>{@code /dashboard/stats} -- Statistics and charts</li>
 *   <li>{@code /dashboard/compare} -- Report diff comparison</li>
 *   <li>{@code /dashboard/audit} -- Scan audit trail</li>
 *   <li>{@code /dashboard/docs} -- Documentation viewer</li>
 *   <li>{@code /dashboard/about} -- About page</li>
 *   <li>{@code /dashboard/settings} -- AI and application settings</li>
 * </ul>
 */
@Controller
public class SentinelViewController {

    private final AwsRegionService awsRegionService;
    private final AwsProfileService awsProfileService;
    private final DocsService docsService;

    /**
     * Constructs the view controller with its required service dependencies.
     *
     * @param awsRegionService  the AWS region service for populating region dropdowns
     * @param awsProfileService the AWS profile service for populating profile dropdowns
     * @param docsService       the documentation service for rendering the docs page
     */
    public SentinelViewController(AwsRegionService awsRegionService, AwsProfileService awsProfileService, DocsService docsService) {
        this.awsRegionService = awsRegionService;
        this.awsProfileService = awsProfileService;
        this.docsService = docsService;
    }

    /**
     * Renders the landing page.
     *
     * <p>GET {@code /}</p>
     *
     * @return the Thymeleaf template name {@code "pages/landing"}
     */
    @GetMapping("/")
    public String landing() {
        return "pages/landing";
    }

    /**
     * Renders the main dashboard page with initial zero-state resource counts and cost overview.
     * The actual data is loaded asynchronously by the frontend JavaScript.
     *
     * <p>GET {@code /dashboard}</p>
     *
     * @param model the Spring MVC model, populated with zero-state counters and available regions
     * @return the Thymeleaf template name {@code "pages/dashboard"}
     */
    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("totalResources", 0);
        model.addAttribute("totalMonthlyCost", 0.0);
        model.addAttribute("idleResourcesCount", 0);
        model.addAttribute("potentialSavings", 0.0);
        model.addAttribute("regionsScanned", 0);
        model.addAttribute("idleResources", Collections.emptyList());
        model.addAttribute("ec2Count", 0);
        model.addAttribute("rdsCount", 0);
        model.addAttribute("ebsCount", 0);
        model.addAttribute("elbCount", 0);
        model.addAttribute("eipCount", 0);
        model.addAttribute("natCount", 0);
        model.addAttribute("cacheCount", 0);
        model.addAttribute("s3Count", 0);
        model.addAttribute("dynamoCount", 0);
        model.addAttribute("regions", awsRegionService.listRegions());
        model.addAttribute("currentPath", "/dashboard");
        return "pages/dashboard";
    }

    /**
     * Renders the scan submission form with available AWS profiles and regions for selection.
     *
     * <p>GET {@code /dashboard/analyse}</p>
     *
     * @param model the Spring MVC model, populated with available regions and profiles
     * @return the Thymeleaf template name {@code "pages/analyse"}
     */
    @GetMapping("/dashboard/analyse")
    public String analyse(Model model) {
        model.addAttribute("availableRegions", awsRegionService.listRegions());
        model.addAttribute("availableProfiles", awsProfileService.listProfiles());
        model.addAttribute("currentPath", "/dashboard/analyse");
        return "pages/analyse";
    }

    /**
     * Renders the resource listing and filtering page.
     *
     * <p>GET {@code /dashboard/resources}</p>
     *
     * @param model the Spring MVC model
     * @return the Thymeleaf template name {@code "pages/resources"}
     */
    @GetMapping("/dashboard/resources")
    public String resources(Model model) {
        model.addAttribute("currentPath", "/dashboard/resources");
        return "pages/resources";
    }

    /**
     * Renders the scan summary view page.
     *
     * <p>GET {@code /dashboard/summary}</p>
     *
     * @param model the Spring MVC model
     * @return the Thymeleaf template name {@code "pages/summary"}
     */
    @GetMapping("/dashboard/summary")
    public String summary(Model model) {
        model.addAttribute("currentPath", "/dashboard/summary");
        return "pages/summary";
    }

    /**
     * Renders the statistics and charts page.
     *
     * <p>GET {@code /dashboard/stats}</p>
     *
     * @param model the Spring MVC model
     * @return the Thymeleaf template name {@code "pages/stats"}
     */
    @GetMapping("/dashboard/stats")
    public String stats(Model model) {
        model.addAttribute("currentPath", "/dashboard/stats");
        return "pages/stats";
    }

    /**
     * Renders the report diff comparison page.
     *
     * <p>GET {@code /dashboard/compare}</p>
     *
     * @param model the Spring MVC model
     * @return the Thymeleaf template name {@code "pages/compare"}
     */
    @GetMapping("/dashboard/compare")
    public String compare(Model model) {
        model.addAttribute("currentPath", "/dashboard/compare");
        return "pages/compare";
    }

    /**
     * Renders the scan audit trail page.
     *
     * <p>GET {@code /dashboard/audit}</p>
     *
     * @param model the Spring MVC model
     * @return the Thymeleaf template name {@code "pages/audit"}
     */
    @GetMapping("/dashboard/audit")
    public String audit(Model model) {
        model.addAttribute("currentPath", "/dashboard/audit");
        return "pages/audit";
    }

    /**
     * Renders the documentation viewer page, populated with documentation tabs loaded from YAML files.
     *
     * <p>GET {@code /dashboard/docs}</p>
     *
     * @param model the Spring MVC model, populated with {@code docTabs} from the DocsService
     * @return the Thymeleaf template name {@code "pages/docs"}
     */
    @GetMapping("/dashboard/docs")
    public String docs(Model model) {
        model.addAttribute("currentPath", "/dashboard/docs");
        model.addAttribute("docTabs", docsService.getTabs());
        return "pages/docs";
    }

    /**
     * Renders the about page.
     *
     * <p>GET {@code /dashboard/about}</p>
     *
     * @param model the Spring MVC model
     * @return the Thymeleaf template name {@code "pages/about"}
     */
    @GetMapping("/dashboard/about")
    public String about(Model model) {
        model.addAttribute("currentPath", "/dashboard/about");
        return "pages/about";
    }

    /**
     * Renders the settings page for AI and application configuration.
     *
     * <p>GET {@code /dashboard/settings}</p>
     *
     * @param model the Spring MVC model
     * @return the Thymeleaf template name {@code "pages/settings"}
     */
    @GetMapping("/dashboard/settings")
    public String settings(Model model) {
        model.addAttribute("currentPath", "/dashboard/settings");
        return "pages/settings";
    }
}
