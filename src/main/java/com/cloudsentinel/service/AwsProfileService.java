package com.cloudsentinel.service;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.ini4j.Ini;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Discovers AWS credential profiles configured on the local machine by parsing
 * {@code ~/.aws/credentials} and {@code ~/.aws/config} files.
 *
 * <p>Profiles are collected from both files and deduplicated using a {@link java.util.LinkedHashSet}
 * to preserve discovery order. The "default" profile is intentionally excluded since the
 * application requires explicit profile selection to prevent accidental scanning of unintended
 * accounts. SSO session sections and service sections in the config file are also excluded.</p>
 *
 * <p>The config file uses a {@code [profile name]} section format (with the "profile " prefix),
 * while the credentials file uses plain {@code [name]} sections. This service handles both
 * formats transparently.</p>
 */
@Service
public class AwsProfileService {

    private static final Logger log = LoggerFactory.getLogger(AwsProfileService.class);
    /** The prefix used in AWS config file sections (e.g., {@code [profile myprofile]}). */
    private static final String PREFIX = "profile ";

    /**
     * Lists all non-default AWS profiles found in the credentials and config files.
     *
     * <p>Reads both {@code ~/.aws/credentials} and {@code ~/.aws/config}, combining and
     * deduplicating profile names. The "default" profile, SSO session sections, and
     * service sections are excluded.</p>
     *
     * @return an unmodifiable list of profile names; may be empty if no profiles are configured
     */
    public List<String> listProfiles() {
        Set<String> profiles = new LinkedHashSet<>();

        readCredentialsFile(profiles);
        readConfigFile(profiles);

        return List.copyOf(profiles);
    }

    /**
     * Parses the {@code ~/.aws/credentials} file and adds non-default profile names to the set.
     *
     * <p>If the file does not exist, this method returns silently. Parse errors are logged
     * as warnings.</p>
     *
     * @param profiles the set to populate with discovered profile names
     */
    private void readCredentialsFile(Set<String> profiles) {
        File file = new File(System.getProperty("user.home"), ".aws/credentials");
        if (!file.exists()) return;
        try {
            Ini ini = new Ini(file);
            for (String section : ini.keySet()) {
                if (!"default".equals(section)) {
                    profiles.add(section);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to parse ~/.aws/credentials: {}", e.getMessage());
        }
    }

    /**
     * Parses the {@code ~/.aws/config} file and adds non-default, non-system profile names
     * to the set.
     *
     * <p>Handles the {@code [profile name]} section format by stripping the "profile " prefix.
     * Sections named "default", "sso-session *", and "services" are excluded.</p>
     *
     * @param profiles the set to populate with discovered profile names
     */
    private void readConfigFile(Set<String> profiles) {
        File file = new File(System.getProperty("user.home"), ".aws/config");
        if (!file.exists()) return;
        try {
            Ini ini = new Ini(file);
            for (String section : ini.keySet()) {
                if (section.startsWith(PREFIX)) {
                    String name = section.substring(PREFIX.length());
                    if (!"default".equals(name)) profiles.add(name);
                } else if (!"default".equals(section) && !section.startsWith("sso-session") && !section.equals("services")) {
                    profiles.add(section);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to parse ~/.aws/config: {}", e.getMessage());
        }
    }
}
