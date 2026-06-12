document.addEventListener('DOMContentLoaded', function() {
    var savedTheme = localStorage.getItem('sentinel-theme') || 'light';
    document.documentElement.setAttribute('data-theme', savedTheme);
    updateThemeIcon(savedTheme);

    var themeToggle = document.getElementById('themeToggle');
    if (themeToggle) {
        themeToggle.addEventListener('click', function() {
            var current = document.documentElement.getAttribute('data-theme') || 'light';
            var next = current === 'light' ? 'medium' : current === 'medium' ? 'dark' : 'light';
            document.documentElement.setAttribute('data-theme', next);
            localStorage.setItem('sentinel-theme', next);
            updateThemeIcon(next);
        });
    }

    var alerts = document.querySelectorAll('.alert-dismissible');
    alerts.forEach(function(alert) {
        setTimeout(function() { alert.style.opacity = '0'; setTimeout(function() { alert.remove(); }, 500); }, 5000);
    });

    initAnalyzeForm();
    initRegionButtons();
    initAuthToggle();
    initAiToggle();
    initResourceFilters();
    initExportCsv();
    checkJobCapacity();
    if (document.getElementById('analyzeForm')) {
        setInterval(checkJobCapacity, 5000);
    }
});

function updateThemeIcon(theme) {
    var icon = document.querySelector('.theme-icon');
    if (icon) icon.textContent = theme === 'light' ? '🌙' : theme === 'medium' ? '🌅' : '☀️';
}

function getTypeColors() {
    return {
        'Application Load Balancer': '#7c3aed',
        'Aurora': '#2563eb',
        'CloudTrail': '#6d28d9',
        'CloudWatch Alarm': '#0369a1',
        'CloudWatch Log Group': '#047857',
        'DynamoDB': '#8b5cf6',
        'EBS': '#f59e0b',
        'EC2': '#2563eb',
        'ECS': '#7c2d12',
        'EKS': '#0d9488',
        'ElastiCache': '#ec4899',
        'Elastic IP': '#ef4444',
        'IAM Role': '#be185d',
        'IAM User': '#b91c1c',
        'KMS': '#a16207',
        'Lambda': '#ea580c',
        'Load Balancer': '#7c3aed',
        'NAT Gateway': '#06b6d4',
        'Network Load Balancer': '#7c3aed',
        'RDS': '#10b981',
        'Redshift': '#9333ea',
        'S3': '#f97316',
        'SNS': '#dc2626',
        'SQS': '#0891b2',
        'SSM Parameter': '#4338ca',
        'Secrets Manager': '#9f1239',
        'VPC': '#4f46e5'
    };
}

function showScanWarning(message) {
    var existing = document.getElementById('scanWarningBanner');
    if (existing) existing.remove();

    var banner = document.createElement('div');
    banner.id = 'scanWarningBanner';
    banner.className = 'alert alert-danger alert-dismissible fade show shadow-lg border-0';
    banner.style.cssText = 'position:fixed;top:1rem;left:50%;transform:translateX(-50%);z-index:9999;max-width:700px;width:90%;border-left:5px solid #dc3545 !important;';
    banner.innerHTML =
        '<button type="button" class="btn-close" onclick="this.parentElement.remove()"></button>' +
        '<div class="d-flex align-items-start">' +
        '<span style="font-size:1.5rem;margin-right:0.75rem;line-height:1;">&#9888;</span>' +
        '<div>' +
        '<h6 class="alert-heading fw-bold mb-1">Incomplete Scan</h6>' +
        '<p class="mb-0">' + message + '</p>' +
        '</div></div>';
    document.body.appendChild(banner);
}

function showCredentialError(message, profileName) {
    var existing = document.getElementById('credentialErrorBanner');
    if (existing) existing.remove();

    // Try to extract profile name from message if not provided
    if (!profileName) {
        var profileMatch = (message || '').match(/PowerUser-\d+/);
        if (profileMatch) profileName = profileMatch[0];
    }
    // Also try the dropdown
    if (!profileName) {
        var profileSelect = document.getElementById('profileSelect');
        if (profileSelect && profileSelect.value) profileName = profileSelect.value;
    }

    var msgLower = (message || '').toLowerCase();
    var isUsingManualCreds = document.getElementById('authModeCredentials') && document.getElementById('authModeCredentials').checked;
    var isUnsupported = msgLower.indexOf('unsupported') >= 0 || msgLower.indexOf('login_session') >= 0 || msgLower.indexOf('signin') >= 0;
    var isSso = !isUsingManualCreds && !isUnsupported && (msgLower.indexOf('sso') >= 0 || msgLower.indexOf('expired') >= 0);
    var title;
    var hint;
    if (isUnsupported) {
        title = 'Unsupported Profile';
        hint = '<hr class="my-2"><p class="mb-0 small">This profile uses a login method that is not supported. ' +
            'Select a different AWS SSO profile from the dropdown, or switch to <strong>Enter Credentials</strong> and paste your Access Key, Secret Key, and Session Token.</p>';
    } else if (isUsingManualCreds) {
        title = 'AWS Credentials Error';
        var hasAsiaKey = false;
        var akField = document.getElementById('accessKeyId');
        if (akField && akField.value && akField.value.toUpperCase().startsWith('ASIA')) hasAsiaKey = true;
        var stField = document.getElementById('sessionToken');
        var missingToken = hasAsiaKey && (!stField || !stField.value || !stField.value.trim());
        hint = '<hr class="my-2"><p class="mb-0 small">' +
            (missingToken
                ? 'Your Access Key starts with <code>ASIA</code>, which indicates temporary credentials. A <strong>Session Token</strong> is required — paste it in the field above and try again.'
                : 'Check that your Access Key, Secret Key, and Session Token are correct and not expired.') +
            '</p>';
    } else if (isSso) {
        title = 'AWS Credentials Expired';
        var profileCmd = profileName ? esc(profileName) : '&lt;your-profile&gt;';
        hint = '<hr class="my-2"><p class="mb-0 small">Run <code>aws sso login --profile ' + profileCmd + '</code> in your terminal to refresh, then try again.</p>';
    } else {
        title = 'AWS Credentials Error';
        hint = '<hr class="my-2"><p class="mb-0 small">Check your AWS profile configuration or try entering credentials manually.</p>';
    }

    var banner = document.createElement('div');
    banner.id = 'credentialErrorBanner';
    banner.className = 'alert alert-danger alert-dismissible fade show shadow-sm';
    banner.style.cssText = 'position:fixed;top:1rem;left:50%;transform:translateX(-50%);z-index:9999;max-width:700px;width:90%;';
    banner.innerHTML =
        '<button type="button" class="btn-close" onclick="this.parentElement.remove()"></button>' +
        '<h6 class="alert-heading fw-bold mb-1">' + title + '</h6>' +
        '<p class="mb-2">' + esc(message) + '</p>' + hint;
    document.body.appendChild(banner);
}

function showBatchConfirmDialog(onConfirm) {
    var existing = document.getElementById('batchConfirmDialog');
    if (existing) existing.remove();

    var overlay = document.createElement('div');
    overlay.id = 'batchConfirmDialog';
    overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);z-index:10000;display:flex;align-items:center;justify-content:center;';

    var dialog = document.createElement('div');
    dialog.style.cssText = 'background:var(--card-bg, #fff);border-radius:0.75rem;padding:1.5rem 2rem;max-width:500px;width:90%;box-shadow:0 20px 60px rgba(0,0,0,0.3);';
    dialog.innerHTML =
        '<h5 style="color:var(--text-primary);margin-bottom:0.75rem;">Batch Scan Already Running</h5>' +
        '<p style="color:var(--text-secondary);margin-bottom:1rem;">A batch scan is currently in progress. Starting a new one will <strong>cancel all active scans</strong> and begin fresh.</p>' +
        '<p style="color:var(--text-muted);font-size:0.85rem;margin-bottom:1.25rem;">Completed scan reports will be preserved.</p>' +
        '<div style="display:flex;gap:0.75rem;justify-content:flex-end;">' +
        '<button id="batchConfirmNo" class="btn btn-outline-secondary btn-sm">Keep Running</button>' +
        '<button id="batchConfirmYes" class="btn btn-danger btn-sm">Cancel &amp; Restart</button>' +
        '</div>';

    overlay.appendChild(dialog);
    document.body.appendChild(overlay);

    document.getElementById('batchConfirmNo').addEventListener('click', function() {
        overlay.remove();
    });
    document.getElementById('batchConfirmYes').addEventListener('click', function() {
        overlay.remove();
        onConfirm();
    });
    // Click outside dialog to dismiss
    overlay.addEventListener('click', function(e) {
        if (e.target === overlay) overlay.remove();
    });
}

function showBatchErrorReport(succeeded, failed, cancelled) {
    var existing = document.getElementById('batchErrorReport');
    if (existing) existing.remove();

    var parts = [];
    if (succeeded.length > 0) parts.push('<span class="text-success fw-bold">' + succeeded.length + ' succeeded</span>');
    if (failed.length > 0) parts.push('<span class="text-danger fw-bold">' + failed.length + ' failed</span>');
    if (cancelled.length > 0) parts.push('<span class="text-muted">' + cancelled.length + ' cancelled</span>');

    var failList = failed.map(function(j) {
        var profile = esc(j.profile_name || j.account_id || 'unknown');
        var msg = esc(j.message || 'Unknown error');
        return '<li><strong>' + profile + '</strong>: ' + msg + '</li>';
    }).join('');

    var banner = document.createElement('div');
    banner.id = 'batchErrorReport';
    banner.className = 'alert alert-warning alert-dismissible fade show shadow-sm';
    banner.style.cssText = 'position:fixed;top:1rem;left:50%;transform:translateX(-50%);z-index:9999;max-width:750px;width:90%;max-height:80vh;overflow-y:auto;';
    banner.innerHTML =
        '<button type="button" class="btn-close" onclick="this.parentElement.remove()"></button>' +
        '<h6 class="alert-heading fw-bold mb-1">Batch Scan Report</h6>' +
        '<p class="mb-2">' + parts.join(' &middot; ') + '</p>' +
        (failed.length > 0 ? '<hr class="my-2"><p class="mb-1 small fw-bold">Failed profiles:</p><ul class="mb-0 small">' + failList + '</ul>' : '');
    document.body.appendChild(banner);
}

function showToast(message, type) {
    var existing = document.querySelector('.sentinel-toast');
    if (existing) existing.remove();
    var isError = type === 'error' || (type !== 'info' && message && (message.toLowerCase().includes('fail') || message.toLowerCase().includes('error') || message.toLowerCase().includes('expired')));
    var bg = isError
        ? 'linear-gradient(135deg,#ef4444,#b91c1c)'
        : 'linear-gradient(135deg,#3b82f6,#1d4ed8)';
    var toast = document.createElement('div');
    toast.className = 'sentinel-toast';
    toast.style.cssText = 'position:fixed;bottom:2rem;right:2rem;background:' + bg + ';color:white;padding:1rem 1.5rem;border-radius:0.75rem;box-shadow:0 10px 25px rgba(0,0,0,0.3);z-index:9999;font-weight:600;transition:opacity 0.3s;max-width:600px;';
    var timeoutId;
    toast.innerHTML = '<div style="display:flex;align-items:flex-start;gap:0.75rem;">' +
        '<span style="flex:1;">' + esc(message) + '</span>' +
        '<button onclick="clearTimeout(this._tid);this.parentElement.parentElement.remove()" style="background:none;border:none;color:white;font-size:1.2rem;cursor:pointer;padding:0;line-height:1;opacity:0.8;">&times;</button>' +
        '</div>';
    document.body.appendChild(toast);
    var duration = isError ? 15000 : (type === 'success' ? 10000 : 7000);
    var closeBtn = toast.querySelector('button');
    timeoutId = setTimeout(function() { toast.style.opacity = '0'; setTimeout(function() { toast.remove(); }, 300); }, duration);
    if (closeBtn) closeBtn._tid = timeoutId;
}

function initAuthToggle() {
    var profileRadio = document.getElementById('authModeProfile');
    var credentialsRadio = document.getElementById('authModeCredentials');
    var profileSection = document.getElementById('profileSection');
    var credentialsSection = document.getElementById('credentialsSection');

    if (!profileRadio || !credentialsRadio) return;

    var scanAllBtn = document.getElementById('scanAllBtn');

    function toggle() {
        if (profileRadio.checked) {
            profileSection.style.display = '';
            credentialsSection.style.display = 'none';
            if (scanAllBtn) scanAllBtn.style.display = '';
        } else {
            profileSection.style.display = 'none';
            credentialsSection.style.display = '';
            if (scanAllBtn) scanAllBtn.style.display = 'none';
        }
    }

    profileRadio.addEventListener('change', toggle);
    credentialsRadio.addEventListener('change', toggle);
}

function initRegionButtons() {
    var regionGroups = [
        { name: 'Africa', color: 'dark', prefixes: ['af-south-'] },
        { name: 'APAC', color: 'warning', prefixes: ['ap-east-', 'ap-northeast-', 'ap-south', 'ap-southeast-'] },
        { name: 'Canada', color: 'info', prefixes: ['ca-'] },
        { name: 'EU', color: 'success', prefixes: ['eu-central-', 'eu-north-', 'eu-south-', 'eu-west-'] },
        { name: 'Israel', color: 'secondary', prefixes: ['il-central-'] },
        { name: 'Middle East', color: 'secondary', prefixes: ['me-central-', 'me-south-'] },
        { name: 'South America', color: 'danger', prefixes: ['sa-east-'] },
        { name: 'US', color: 'primary', prefixes: ['us-east-', 'us-west-'] }
    ];

    var badgeContainer = document.getElementById('regionGroupBadges');
    var checkboxes = document.querySelectorAll('.region-checkbox');
    if (!badgeContainer || checkboxes.length === 0) return;

    // Build clickable group badges
    regionGroups.forEach(function(group) {
        var matchingBoxes = Array.from(checkboxes).filter(function(cb) {
            return group.prefixes.some(function(p) { return cb.value.startsWith(p); });
        });
        if (matchingBoxes.length === 0) return;

        var badge = document.createElement('button');
        badge.type = 'button';
        badge.className = 'btn btn-outline-' + group.color + ' btn-sm px-3 py-1 rounded-pill';
        badge.textContent = group.name + ' (' + matchingBoxes.length + ')';
        badge.dataset.groupPrefixes = JSON.stringify(group.prefixes);
        badge.dataset.color = group.color;
        badge.addEventListener('click', function(e) {
            e.preventDefault();
            var allChecked = matchingBoxes.every(function(cb) { return cb.checked; });
            matchingBoxes.forEach(function(cb) { cb.checked = !allChecked; });
            updateBadgeStyle(badge, !allChecked, group.color);
        });
        badgeContainer.appendChild(badge);
    });

    function updateBadgeStyle(badge, active, color) {
        if (active) {
            badge.className = 'btn btn-' + color + ' btn-sm px-3 py-1 rounded-pill';
        } else {
            badge.className = 'btn btn-outline-' + color + ' btn-sm px-3 py-1 rounded-pill';
        }
    }

    function syncBadges() {
        badgeContainer.querySelectorAll('button').forEach(function(badge) {
            var prefixes = JSON.parse(badge.dataset.groupPrefixes);
            var matchingBoxes = Array.from(checkboxes).filter(function(cb) {
                return prefixes.some(function(p) { return cb.value.startsWith(p); });
            });
            var allChecked = matchingBoxes.length > 0 && matchingBoxes.every(function(cb) { return cb.checked; });
            updateBadgeStyle(badge, allChecked, badge.dataset.color);
        });
    }

    // Sync badge styles when individual checkboxes change
    document.getElementById('regionCheckboxGrid').addEventListener('change', syncBadges);

    // Select All / Clear All (global)
    var selectAll = document.getElementById('selectAllRegions');
    var clearAll = document.getElementById('clearAllRegions');

    if (selectAll) {
        selectAll.addEventListener('click', function(e) {
            e.preventDefault();
            checkboxes.forEach(function(cb) { cb.checked = true; });
            syncBadges();
        });
    }
    if (clearAll) {
        clearAll.addEventListener('click', function(e) {
            e.preventDefault();
            checkboxes.forEach(function(cb) { cb.checked = false; });
            syncBadges();
        });
    }
}

function updateAiAvailabilityBanner() {
    var banner = document.getElementById('aiUnavailableBanner');
    if (!banner) return;
    var aiCheckbox = document.getElementById('enableAiFiltering');
    if (!aiCheckbox || !aiCheckbox.checked) {
        banner.style.display = 'none';
        return;
    }
    var selectedProvider = document.querySelector('input[name="aiProvider"]:checked');
    var provider = selectedProvider ? selectedProvider.value : 'bedrock';
    var available = window._aiProviderAvailability && window._aiProviderAvailability[provider];
    if (!available) {
        // For Ollama, check if warmup is still in progress before showing the warning
        if (provider === 'ollama' && !window._ollamaWarmupDone) {
            banner.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span> Ollama is warming up — checking availability...';
            banner.className = 'alert alert-info py-2 mb-3 small border-0';
            banner.style.display = '';
            // Poll health until warmup resolves
            if (!window._ollamaWarmupPollActive) {
                window._ollamaWarmupPollActive = true;
                var poll = setInterval(function() {
                    fetch('/ai/health').then(function(r) { return r.json(); }).then(function(h) {
                        var status = h.ollama_status;
                        if (status === 'ready' || status === 'error' || status === 'unavailable') {
                            clearInterval(poll);
                            window._ollamaWarmupPollActive = false;
                            window._ollamaWarmupDone = true;
                            if (status === 'ready') {
                                window._aiProviderAvailability.ollama = true;
                            }
                            updateAiAvailabilityBanner();
                        }
                    }).catch(function() {});
                }, 3000);
            }
            return;
        }
        var providerLabel = provider.charAt(0).toUpperCase() + provider.slice(1);
        banner.innerHTML = '<strong>' + providerLabel + ' is not available.</strong> The scan will proceed with rules-based checks only — no AI insights will be generated. You can cancel and switch providers, or continue without AI.';
        banner.className = 'alert alert-warning py-2 mb-3 small border-0';
        banner.style.display = '';
    } else {
        banner.style.display = 'none';
    }
}

function initAiToggle() {
    var aiCheckbox = document.getElementById('enableAiFiltering');
    var aiProviderSection = document.getElementById('aiProviderSection');

    if (!aiCheckbox || !aiProviderSection) return;

    aiCheckbox.addEventListener('change', function() {
        aiProviderSection.style.display = this.checked ? '' : 'none';
        updateAiAvailabilityBanner();
    });

    // Update model dropdown when provider changes
    var providerRadios = document.querySelectorAll('input[name="aiProvider"]');
    providerRadios.forEach(function(radio) {
        radio.addEventListener('change', function() {
            if (window._aiModelsLoaded) {
                updateModelDropdown(this.value);
            } else {
                loadAiModels();
            }
            updateAiAvailabilityBanner();
        });
    });

    // Load models eagerly on page load
    loadAiModels();
}

function loadAiModels() {
    var sel = document.getElementById('aiModelSelect');
    if (sel) sel.innerHTML = '<option value="">Loading models...</option>';
    // Load both status and model capabilities in parallel
    Promise.all([
        fetch('/ai/status').then(function(r) { return r.json(); }),
        fetch('/ai/ollama/models').then(function(r) { return r.json(); }).catch(function() { return { models: [] }; })
    ]).then(function(results) {
        var data = results[0];
        var ollamaDetails = results[1];
        window._aiModelsLoaded = true;
        window._aiProviderModels = {};
        window._aiProviderAvailability = {};
        window._ollamaModelCapabilities = {};
        if (data.providers) {
            if (data.providers.bedrock) {
                window._aiProviderModels.bedrock = data.providers.bedrock.models || [];
                window._aiProviderAvailability.bedrock = data.providers.bedrock.available || false;
            }
            if (data.providers.ollama) {
                window._aiProviderModels.ollama = data.providers.ollama.models || [];
                window._aiProviderAvailability.ollama = data.providers.ollama.available || false;
            }
        }
        // Store per-model capability info from /ai/ollama/models
        (ollamaDetails.models || []).forEach(function(m) {
            window._ollamaModelCapabilities[m.name] = {
                full_scan_capable: m.full_scan_capable || false,
                size: m.size || 0
            };
        });
        var selectedProvider = document.querySelector('input[name="aiProvider"]:checked');
        updateModelDropdown(selectedProvider ? selectedProvider.value : 'bedrock');
        updateAiAvailabilityBanner();
    }).catch(function() {
        var sel = document.getElementById('aiModelSelect');
        if (sel) sel.innerHTML = '<option value="">Failed to load models</option>';
    });
}

function isFullScan() {
    var catRadio = document.querySelector('input[name="scanCategory"]:checked');
    if (!catRadio) return true;
    var cat = catRadio.value || '';
    return cat === '' || cat === 'FULL' || cat === 'full';
}

function updateModelDropdown(provider) {
    var sel = document.getElementById('aiModelSelect');
    if (!sel) return;
    var models = (window._aiProviderModels && window._aiProviderModels[provider]) || [];
    var fullScan = isFullScan();
    sel.innerHTML = '';

    // Bedrock models always handle full scans; Ollama models are filtered by capability
    var filtered = models;
    if (provider === 'ollama' && fullScan) {
        filtered = models.filter(function(m) {
            var cap = (window._ollamaModelCapabilities || {})[m];
            return cap ? cap.full_scan_capable : true; // if no capability info, assume capable
        });
    }

    if (filtered.length === 0 && models.length > 0) {
        sel.innerHTML = '<option value="" disabled>No models suitable for full scan</option>';
        // Show all models as disabled fallback with warning
        models.forEach(function(m) {
            var opt = document.createElement('option');
            opt.value = m;
            opt.textContent = m + ' (may timeout on full scan)';
            opt.style.color = '#d97706';
            sel.appendChild(opt);
        });
        showToast('Small Ollama models may timeout on full scans. Consider using Bedrock or a larger model.', 'warning');
        return;
    }
    if (filtered.length === 0) {
        sel.innerHTML = '<option value="">No models configured</option>';
        return;
    }
    filtered.forEach(function(m, i) {
        var opt = document.createElement('option');
        opt.value = m;
        var cap = (window._ollamaModelCapabilities || {})[m];
        var sizeLabel = cap ? ' (' + (cap.size / (1024*1024*1024)).toFixed(1) + ' GB)' : '';
        opt.textContent = m + sizeLabel;
        if (i === 0) opt.selected = true;
        sel.appendChild(opt);
    });
}

function initAnalyzeForm() {
    var form = document.getElementById('analyzeForm');
    if (!form) return;

    // Refresh model dropdown when scan category changes (filters out small models for full scans)
    document.querySelectorAll('input[name="scanCategory"]').forEach(function(radio) {
        radio.addEventListener('change', function() {
            var provider = document.querySelector('input[name="aiProvider"]:checked');
            updateModelDropdown(provider ? provider.value : 'bedrock');
        });
    });

    form.addEventListener('submit', function(e) {
        e.preventDefault();

        var useProfile = document.getElementById('authModeProfile').checked;
        var payload = { regions: [], enable_ai_filter: false, ai_provider: 'bedrock' };

        var checked = document.querySelectorAll('.region-checkbox:checked');
        checked.forEach(function(cb) { payload.regions.push(cb.value); });
        if (payload.regions.length === 0) {
            showToast('Please select at least one region');
            return;
        }

        var aiCheckbox = document.getElementById('enableAiFiltering');
        if (aiCheckbox) payload.enable_ai_filter = aiCheckbox.checked;

        var selectedProviderRadio = document.querySelector('input[name="aiProvider"]:checked');
        if (selectedProviderRadio) payload.ai_provider = selectedProviderRadio.value;

        var aiModelSelect = document.getElementById('aiModelSelect');
        if (aiModelSelect && aiModelSelect.value) payload.ai_model = aiModelSelect.value;

        var scanCategoryRadio = document.querySelector('input[name="scanCategory"]:checked');
        if (scanCategoryRadio) payload.scan_category = scanCategoryRadio.value;

        if (useProfile) {
            var profileInput = document.getElementById('profileName');
            var profileValue = profileInput ? profileInput.value.trim() : '';
            payload.profile_name = profileValue || 'default';
        } else {
            var ak = document.getElementById('accessKeyId');
            var sk = document.getElementById('secretAccessKey');
            var st = document.getElementById('sessionToken');
            var label = document.getElementById('credentialLabel');
            if (ak && ak.value && sk && sk.value) {
                payload.credentials = {
                    access_key_id: ak.value,
                    secret_access_key: sk.value,
                    session_token: st && st.value ? st.value : null
                };
                payload.profile_name = (label && label.value.trim()) || 'manual-' + ak.value.substring(ak.value.length - 4);
            } else {
                showToast('Please enter AWS credentials');
                return;
            }
        }

        var profileForCheck = payload.profile_name || '';
        fetch('/analyse/status?profileName=' + encodeURIComponent(profileForCheck))
            .then(function(r) { return r.json(); })
            .then(function(status) {
                if (status.has_recent_report) {
                    if (!confirm('A report from ' + status.report_age_hours + ' hour(s) ago exists for account ' +
                            status.account_id + '.\n\nShow cached report?\n\nClick OK for cached, Cancel for fresh scan.')) {
                        submitJob(payload);
                    } else {
                        fetch('/analyse/cached?profileName=' + encodeURIComponent(profileForCheck))
                            .then(function(r) {
                                if (r.status === 204) return null;
                                return r.json();
                            })
                            .then(function(report) {
                                if (report) {
                                    sessionStorage.setItem('sentinel-report', JSON.stringify(report));
                                    window.location.href = '/dashboard';
                                } else {
                                    showToast('No report found', 'info');
                                }
                            });
                    }
                } else {
                    submitJob(payload);
                }
            })
            .catch(function() { submitJob(payload); });
    });

    function submitJob(payload) {
        var btn = form.querySelector('button[type="submit"]');
        if (btn) btn.disabled = true;
        showProgress('Submitting analysis...', 0);
        sessionStorage.setItem('sentinel-job-pending', 'true');

        fetch('/analyse', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        .then(function(r) {
            if (r.status === 429) {
                return r.json().then(function(data) {
                    showCapacityWarning(data);
                    throw new Error('__capacity__');
                });
            }
            if (!r.ok) return r.json().then(function(d) { throw new Error(d.detail || 'Failed'); });
            return r.json();
        })
        .then(function(data) {
            sessionStorage.removeItem('sentinel-job-pending');
            sessionStorage.setItem('sentinel-job-id', data.job_id);
            if (data.already_running) {
                showProgress('Scan already in progress...', 0);
                showToast(data.message || 'Scan already in progress', 'info');
            } else {
                showProgress('Queued...', 5);
                showToast('Analysis queued');
            }
            pollJob(data.job_id);
            if (btn) btn.disabled = false;
            checkJobCapacity();
        })
        .catch(function(err) {
            sessionStorage.removeItem('sentinel-job-pending');
            hideProgress();
            if (err.message !== '__capacity__') showToast('Error: ' + err.message);
            if (btn) btn.disabled = false;
        });
    }

    function showCapacityWarning(data) {
        var area = document.getElementById('errorArea');
        if (!area) { showToast(data.detail); return; }

        var jobLinks = (data.active_jobs || []).map(function(j) {
            return '<li class="mb-1">' +
                '<strong>' + esc(j.account_id) + '</strong> (' + esc(j.profile_name) + ') — ' +
                '<span class="badge bg-info">' + esc(j.phase) + ' ' + j.progress + '%</span>' +
                '</li>';
        }).join('');

        area.innerHTML =
            '<div class="alert alert-warning border-0 shadow-sm">' +
            '<strong>All analysis slots are in use</strong>' +
            '<p class="mb-2 mt-1">' + esc(data.detail) + '</p>' +
            '<ul class="mb-0">' + jobLinks + '</ul>' +
            '</div>';
    }
}

function checkJobCapacity() {
    var btn = document.getElementById('analyzeButton');
    var btnText = document.getElementById('analyzeButtonText');
    if (!btn) return;

    fetch('/analyse/jobs')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var active = (data.jobs || []).filter(function(j) { return j.phase !== 'complete' && j.phase !== 'error' && j.phase !== 'cancelled'; });
            var area = document.getElementById('errorArea');

            if (data.at_capacity) {
                btn.disabled = true;
                if (btnText) btnText.textContent = 'All ' + data.capacity + ' slots in use — please wait';
                btn.classList.remove('btn-primary');
                btn.classList.add('btn-secondary');
            } else {
                btn.disabled = false;
                if (btnText) btnText.textContent = 'Analyse Resources';
                btn.classList.remove('btn-secondary');
                btn.classList.add('btn-primary');
            }

            if (active.length > 0 && area) {
                var jobItems = active.map(function(j) {
                    var phaseColors = { queued: 'secondary', scanning: 'primary', ai: 'info', saving: 'success', complete: 'success', error: 'danger' };
                    var pColor = phaseColors[j.phase] || 'primary';
                    return '<div class="d-flex align-items-center mb-1 gap-2">' +
                        '<strong class="small" style="white-space:nowrap;">' + esc(j.profile_name) + '</strong>' +
                        '<span class="badge bg-' + pColor + '" style="font-size:0.6rem;">' + esc(j.phase) + '</span>' +
                        '<div class="progress flex-grow-1" style="height:6px;"><div class="progress-bar progress-bar-striped progress-bar-animated bg-' + pColor + '" style="width:' + j.progress + '%;"></div></div>' +
                        '<span class="small text-muted" style="font-size:0.7rem; white-space:nowrap;">' + j.progress + '%</span>' +
                        '<button class="btn btn-outline-danger btn-sm py-0 px-1" style="font-size:0.6rem;" onclick="stopJob(\'' + esc(j.job_id) + '\')">Stop</button>' +
                        '</div>';
                }).join('');

                area.innerHTML =
                    '<div class="card border-0 shadow-sm mb-3"><div class="card-body py-2">' +
                    '<strong class="small" style="color:var(--text-primary);">Active Scans: ' + active.length + (active.length >= data.capacity ? ' (at capacity)' : '') + '</strong>' +
                    '<div class="mt-1">' + jobItems + '</div>' +
                    '</div></div>';
            } else if (area && active.length === 0) {
                area.innerHTML = '';
            }
        })
        .catch(function() {});
}

function showProgress(message, percent) {
    var bar = document.getElementById('globalProgressBar');
    var msg = document.getElementById('progressMessage');
    var fill = document.getElementById('progressBarFill');
    var pct = document.getElementById('progressPercent');
    if (bar) bar.style.display = '';
    if (msg) msg.textContent = message || 'Processing...';
    var p = (typeof percent === 'number') ? percent : 0;
    if (fill) fill.style.width = p + '%';
    if (pct) pct.textContent = p + '%';
}

function hideProgress() {
    var bar = document.getElementById('globalProgressBar');
    if (bar) bar.style.display = 'none';
}

function cancelCurrentJob() {
    var btn = document.getElementById('cancelJobBtn');
    if (btn) btn.disabled = true;

    // Cancel ALL jobs (active + queued) and clear finished ones
    fetch('/analyse/cancel-all', { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            hideProgress();
            sessionStorage.removeItem('sentinel-job-id');
            window._lastBatchCompleted = 0;
            window._batchRunning = false;
            showToast(data.message || 'All scans stopped', 'info');
            if (btn) btn.disabled = false;
            checkJobCapacity();
        })
        .catch(function() {
            showToast('Failed to stop scans');
            if (btn) btn.disabled = false;
        });
}

var _pollRetryCount = 0;
function pollJob(jobId) {
    fetch('/analyse/job?jobId=' + encodeURIComponent(jobId))
        .then(function(r) { _pollRetryCount = 0; return r.json(); })
        .then(function(status) {
            if (status.phase === 'unknown') {
                hideProgress();
                sessionStorage.removeItem('sentinel-job-id');
                return;
            }

            showProgress(status.message, status.progress);

            if (status.phase === 'complete') {
                hideProgress();
                sessionStorage.removeItem('sentinel-job-id');
                if (status.report) {
                    sessionStorage.setItem('sentinel-report', JSON.stringify(status.report));
                    var total = status.report.analysis_response ? status.report.analysis_response.total_resources : 0;
                    showToast('Analysis complete: ' + total + ' resources found');
                }
                if (window.location.pathname === '/dashboard') {
                    loadResultsFromSession();
                    var noMsg = document.getElementById('noAnalysisMessage');
                    if (noMsg) noMsg.style.display = 'none';
                } else {
                    window.location.href = '/dashboard';
                }
            } else if (status.phase === 'cancelled') {
                hideProgress();
                sessionStorage.removeItem('sentinel-job-id');
                showToast('Scan cancelled', 'info');
            } else if (status.phase === 'error') {
                hideProgress();
                sessionStorage.removeItem('sentinel-job-id');
                var msg = (status.message || '').toLowerCase();
                var isCredError = (msg.indexOf('sso') >= 0 || msg.indexOf('expired') >= 0)
                    && msg.indexOf('unsupported') < 0;
                if (isCredError) {
                    showCredentialError(status.message);
                } else {
                    showToast('Analysis failed: ' + status.message, 'error');
                }
                var btn = document.getElementById('analyzeButton');
                if (btn) btn.disabled = false;
            } else {
                setTimeout(function() { pollJob(jobId); }, 1500);
            }
        })
        .catch(function() {
            if (++_pollRetryCount > 20) {
                hideProgress();
                showToast('Lost connection to server', 'error');
                sessionStorage.removeItem('sentinel-job-id');
                return;
            }
            setTimeout(function() { pollJob(jobId); }, 3000);
        });
}

function resumeJobIfRunning() {
    var jobId = sessionStorage.getItem('sentinel-job-id');
    if (jobId) {
        fetch('/analyse/job?jobId=' + encodeURIComponent(jobId))
            .then(function(r) { return r.json(); })
            .then(function(status) {
                if (status.phase === 'unknown' || status.phase === 'complete' || status.phase === 'error') {
                    sessionStorage.removeItem('sentinel-job-id');
                    if (status.phase === 'complete' && status.report) {
                        sessionStorage.setItem('sentinel-report', JSON.stringify(status.report));
                    }
                    return;
                }
                showProgress(status.message || 'Processing...', status.progress || 0);
                pollJob(jobId);
            })
            .catch(function() {
                sessionStorage.removeItem('sentinel-job-id');
            });
        return;
    }

    // Check for any active jobs (including batch scans) and resume progress bar
    fetch('/analyse/jobs')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var active = (data.jobs || []).filter(function(j) {
                return j.phase !== 'complete' && j.phase !== 'error' && j.phase !== 'cancelled';
            });
            if (active.length > 1) {
                // Multiple active jobs = batch scan — resume batch polling only
                sessionStorage.removeItem('sentinel-job-id');
                window._batchRunning = true;
                var total = (data.jobs || []).length;
                pollBatchProgress(total);
            } else if (active.length === 1) {
                var job = active[0];
                sessionStorage.setItem('sentinel-job-id', job.job_id);
                showProgress(job.message || 'Processing...', job.progress || 0);
                pollJob(job.job_id);
            }
            sessionStorage.removeItem('sentinel-job-pending');
        })
        .catch(function() {
            sessionStorage.removeItem('sentinel-job-pending');
        });
}

function loadResultsFromSession() {
    var raw = sessionStorage.getItem('sentinel-report');
    if (!raw) {
        raw = sessionStorage.getItem('sentinel-results');
        if (raw) {
            try {
                var oldData = JSON.parse(raw);
                populateDashboard(oldData);
                populateAiInsights(oldData.ai_insights);
                populateCorrelations(oldData.resources || []);
                renderCharts(oldData.resources || []);
                window._sentinelResources = oldData.resources || [];
            } catch (e) {}
        }
        return;
    }
    try {
        var report = JSON.parse(raw);
        var data = report.analysis_response || {};
        populateDashboard(data);
        populateScanSummary(data);
        populateRegionDistribution(data);
        populateAiInsights(data.ai_insights);
        populateCorrelations(data.resources || []);
        renderCharts(data.resources || []);
        // Only show report meta badges if an account is selected in the dropdown
        var selector = document.getElementById('dashAccountSelector');
        if (selector && selector.value) {
            populateReportMeta(report);
        }
        populateDiff(report.diff_summary);
        window._sentinelResources = data.resources || [];
    } catch (e) {}
}

function populateReportMeta(report) {
    var accountEl = document.getElementById('dashAccountId');
    var profileEl = document.getElementById('dashProfileName');
    var aiEl = document.getElementById('dashAiStatus');

    if (report.account_id) {
        setText('dashAccountId', report.account_id);
        if (accountEl) accountEl.style.display = '';
    }
    if (report.profile_name) {
        setText('dashProfileName', report.profile_name);
        if (profileEl) profileEl.style.display = '';
    }
    var scannedAt = report.scanned_at ? new Date(report.scanned_at).toLocaleString() : '-';
    setText('dashTimestamp', scannedAt);
    var metaSection = document.getElementById('reportMetaSection');
    if (metaSection) metaSection.style.display = '';

    // Scan category badge
    var scanCatEl = document.getElementById('dashScanCategory');
    if (scanCatEl && report.analysis_response) {
        var cat = report.analysis_response.scan_category;
        if (cat) {
            var catLabel = cat === 'COST_OPTIMIZATION' ? 'Cost & Idle' : cat === 'SECURITY_GOVERNANCE' ? 'Security' : 'Full Scan';
            var catColor = cat === 'COST_OPTIMIZATION' ? 'success' : cat === 'SECURITY_GOVERNANCE' ? 'warning' : 'primary';
            scanCatEl.innerHTML = '<span class="badge bg-' + catColor + '">' + catLabel + '</span>';
            scanCatEl.style.display = '';
        }
    }

    // AI status badge on dashboard
    if (aiEl && report.analysis_response) {
        var ai = report.analysis_response.ai_filtering;
        var insights = report.analysis_response.ai_insights;
        var aiFailed = ai && ai.enabled && ai.truly_idle_count === 0 && insights && insights.executive_summary && insights.executive_summary.indexOf('failed') >= 0;
        if (ai && ai.enabled && !aiFailed) {
            var model = ai.ai_model || ai.provider || 'AI';
            aiEl.innerHTML = '<span class="badge bg-success">AI: ' + esc(model) + '</span>';
            aiEl.style.display = '';
        } else if (aiFailed) {
            var model = ai.ai_model || ai.provider || 'AI';
            aiEl.innerHTML = '<span class="badge bg-warning text-dark">AI: ' + esc(model) + ' (failed)</span>';
            aiEl.style.display = '';
        } else {
            aiEl.innerHTML = '<span class="badge" style="border:1px solid #aaa; color:#888;">AI: Off</span>';
            aiEl.style.display = '';
        }
    }
}

function populateScanSummary(data) {
    var section = document.getElementById('scanSummarySection');
    if (!section || !data || !data.resources || data.resources.length === 0) return;

    section.style.display = '';
    var resources = data.resources;
    var totalCost = data.total_monthly_cost || 0;
    var idleCount = data.actionable_findings_count || data.idle_resources_count || 0;
    var savings = data.potential_savings || 0;
    var regions = data.analyzed_regions || [];

    // Summary text
    var summaryText = 'Scanned ' + resources.length + ' resources across ' + regions.length + ' region' + (regions.length !== 1 ? 's' : '') + '. ';
    if (idleCount > 0) {
        summaryText += idleCount + ' resource' + (idleCount !== 1 ? 's are' : ' is') + ' idle or unused, representing $' + savings.toFixed(2) + '/month in potential savings (' + (totalCost > 0 ? Math.round(savings / totalCost * 100) : 0) + '% of total spend).';
    } else {
        summaryText += 'No idle resources detected. Total monthly cost: $' + totalCost.toFixed(2) + '.';
    }
    setText('scanSummaryText', summaryText);

    // Top cost areas — group by resource type, sort by total cost
    var costByType = {};
    resources.forEach(function(r) {
        var type = r.resource_type || 'Other';
        costByType[type] = (costByType[type] || 0) + (r.monthly_cost_usd || 0);
    });
    var topCost = Object.entries(costByType)
        .filter(function(e) { return e[1] > 0; })
        .sort(function(a, b) { return b[1] - a[1]; })
        .slice(0, 5);

    var topCostEl = document.getElementById('topCostAreas');
    if (topCostEl) {
        if (topCost.length === 0) {
            topCostEl.innerHTML = '<span class="text-muted">No cost data available</span>';
        } else {
            topCostEl.innerHTML = topCost.map(function(e) {
                var pct = totalCost > 0 ? Math.round(e[1] / totalCost * 100) : 0;
                return '<div class="d-flex justify-content-between mb-1"><span>' + esc(e[0]) + '</span><strong>$' + e[1].toFixed(2) + ' <span class="text-muted fw-normal">(' + pct + '%)</span></strong></div>';
            }).join('');
        }
    }

    // Highest savings — individual resources sorted by cost, only idle/actionable
    var actionable = resources.filter(function(r) {
        var rec = r.recommendation || '';
        return (r.monthly_cost_usd > 0) && (rec.includes('Idle') || rec.includes('Terminat') || rec.includes('Delete') || rec.includes('Release') || rec.includes('Unused') || rec.includes('Empty') || rec.includes('Downsiz'));
    }).sort(function(a, b) { return (b.monthly_cost_usd || 0) - (a.monthly_cost_usd || 0); }).slice(0, 5);

    var topSavingsEl = document.getElementById('topSavings');
    if (topSavingsEl) {
        if (actionable.length === 0) {
            topSavingsEl.innerHTML = '<span class="text-muted">No actionable savings found</span>';
        } else {
            topSavingsEl.innerHTML = actionable.map(function(r) {
                return '<div class="d-flex justify-content-between mb-1"><span>' + esc(r.resource_type) + ': ' + esc((r.resource_name || r.resource_id || '').substring(0, 30)) + '</span><strong class="text-danger">$' + (r.monthly_cost_usd || 0).toFixed(2) + '</strong></div>';
            }).join('');
        }
    }

    // Attention required — count by recommendation category
    var attentionCounts = {idle: 0, security: 0, governance: 0, low: 0, review: 0, stopped: 0};
    resources.forEach(function(r) {
        var rec = (r.recommendation || '').toLowerCase();
        if (rec.includes('idle') || rec.includes('unused') || rec.includes('empty') || rec.includes('delete') || rec.includes('release')) attentionCounts.idle++;
        else if (rec.includes('rotate') || rec.includes('enable') || rec.includes('expired') || rec.includes('exposed') || rec.includes('misconfigured')) attentionCounts.security++;
        else if (rec.includes('stale') || rec.includes('missing')) attentionCounts.governance++;
        else if (rec.includes('low')) attentionCounts.low++;
        else if (rec.includes('review')) attentionCounts.review++;
        else if (rec.includes('stopped')) attentionCounts.stopped++;
    });

    var attEl = document.getElementById('attentionRequired');
    if (attEl) {
        var items = [];
        if (attentionCounts.idle > 0) items.push('<div class="mb-1"><span class="badge bg-danger me-1">' + attentionCounts.idle + '</span> Idle/Unused resources</div>');
        if (attentionCounts.security > 0) items.push('<div class="mb-1"><span class="badge me-1" style="background:#f56565;">' + attentionCounts.security + '</span> Security findings</div>');
        if (attentionCounts.governance > 0) items.push('<div class="mb-1"><span class="badge me-1" style="background:#667eea;">' + attentionCounts.governance + '</span> Governance findings</div>');
        if (attentionCounts.stopped > 0) items.push('<div class="mb-1"><span class="badge bg-secondary me-1">' + attentionCounts.stopped + '</span> Stopped resources</div>');
        if (attentionCounts.low > 0) items.push('<div class="mb-1"><span class="badge bg-warning text-dark me-1">' + attentionCounts.low + '</span> Low utilisation</div>');
        if (attentionCounts.review > 0) items.push('<div class="mb-1"><span class="badge bg-info me-1">' + attentionCounts.review + '</span> Need review</div>');
        attEl.innerHTML = items.length > 0 ? items.join('') : '<span class="text-muted">All resources healthy</span>';
    }
}

function populateRegionDistribution(data) {
    var section = document.getElementById('regionDistSection');
    if (!section || !data || !data.resources || data.resources.length === 0) return;

    section.style.display = '';
    var resources = data.resources;
    var analyzedRegions = data.analyzed_regions || [];

    // Count resources and cost per region
    var regionData = {};
    resources.forEach(function(r) {
        var reg = r.region || 'unknown';
        if (!regionData[reg]) regionData[reg] = { count: 0, cost: 0, types: {} };
        regionData[reg].count++;
        regionData[reg].cost += (r.monthly_cost_usd || 0);
        regionData[reg].types[r.resource_type || 'Unknown'] = true;
    });

    // Classify: active (3+), sparse (1-2), empty (0)
    var activeRegions = [];
    var sparseRegions = [];
    var regionsWithResources = Object.keys(regionData);

    Object.entries(regionData)
        .sort(function(a, b) { return b[1].count - a[1].count; })
        .forEach(function(entry) {
            var reg = entry[0];
            var d = entry[1];
            if (d.count <= 2 && reg !== 'global') {
                sparseRegions.push({ name: reg, count: d.count, cost: d.cost, types: Object.keys(d.types) });
            } else {
                activeRegions.push({ name: reg, count: d.count, cost: d.cost });
            }
        });

    var emptyRegions = analyzedRegions.filter(function(r) {
        return !regionsWithResources.includes(r);
    }).sort();

    // Render active regions
    var activeEl = document.getElementById('activeRegionsList');
    if (activeEl) {
        activeEl.innerHTML = activeRegions.map(function(r) {
            return '<div class="d-flex justify-content-between mb-1"><span>' + esc(r.name) + ' <span class="text-muted">(' + r.count + ')</span></span><strong>$' + r.cost.toFixed(2) + '</strong></div>';
        }).join('') || '<span class="text-muted">None</span>';
    }

    // Render sparse regions with warning
    var sparseEl = document.getElementById('sparseRegionsList');
    if (sparseEl) {
        if (sparseRegions.length === 0) {
            sparseEl.innerHTML = '<span class="text-muted">None — no isolated resources</span>';
        } else {
            sparseEl.innerHTML = sparseRegions.map(function(r) {
                return '<div class="mb-2"><strong>' + esc(r.name) + '</strong><br><span class="text-muted">' + r.count + ' resource' + (r.count > 1 ? 's' : '') + ': ' + r.types.join(', ') + '</span></div>';
            }).join('');
        }
    }

    // Store empty regions for toggle
    window._emptyRegions = emptyRegions;
    var emptyContainer = document.getElementById('emptyRegionsContainer');
    var emptyEl = document.getElementById('emptyRegionsList');
    if (emptyEl) {
        emptyEl.innerHTML = emptyRegions.length > 0
            ? emptyRegions.map(function(r) { return '<span class="badge bg-light text-muted me-1 mb-1" style="border:1px solid #ddd;">' + r + '</span>'; }).join('')
            : '<span class="text-muted">All scanned regions have resources</span>';
    }
}

function toggleEmptyRegions() {
    var container = document.getElementById('emptyRegionsContainer');
    var btn = event.target;
    if (container) {
        var showing = container.style.display !== 'none';
        container.style.display = showing ? 'none' : '';
        btn.textContent = showing ? 'Show empty regions' : 'Hide empty regions';
    }
}

function populateDiff(diff) {
    var section = document.getElementById('diffSection');
    if (!section || !diff) return;
    if (diff.resources_added === 0 && diff.resources_removed === 0 && Math.abs(diff.cost_change) < 0.01) return;

    section.style.display = '';
    setText('diffNarrative', diff.narrative || '');

    var body = document.getElementById('diffBody');
    if (!body) return;
    body.innerHTML = '';

    var items = [
        { label: 'Resources Added', value: '+' + diff.resources_added, cls: diff.resources_added > 0 ? 'text-success' : '' },
        { label: 'Resources Removed', value: '-' + diff.resources_removed, cls: diff.resources_removed > 0 ? 'text-danger' : '' },
        { label: 'Cost Change', value: (diff.cost_change >= 0 ? '+' : '') + '$' + diff.cost_change.toFixed(2), cls: diff.cost_change > 0 ? 'text-danger' : 'text-success' },
        { label: 'Idle Count Change', value: (diff.idle_count_change >= 0 ? '+' : '') + diff.idle_count_change, cls: diff.idle_count_change > 0 ? 'text-danger' : 'text-success' }
    ];

    items.forEach(function(item) {
        var div = document.createElement('div');
        div.className = 'col-md-3 text-center';
        div.innerHTML = '<h6 class="text-muted small">' + item.label + '</h6><h4 class="' + item.cls + '">' + item.value + '</h4>';
        body.appendChild(div);
    });
}

function populateDashboard(data) {
    if (!data) return;
    if (data.credential_error) {
        showCredentialError(data.credential_error);
    } else if (data.scanner_failure_count > 0 && data.total_resources === 0) {
        showCredentialError('Scan completed but all ' + data.scanner_failure_count +
            ' scanner tasks failed. This usually means AWS credentials have expired. ' +
            'Run "aws sso login" to refresh.');
    }

    setText('dashTotalResources', data.total_resources || 0);
    setText('dashMonthlyCost', '$' + (data.total_monthly_cost || 0).toFixed(2));
    setText('dashIdleCount', data.actionable_findings_count || data.idle_resources_count || 0);
    setText('dashPotentialSavings', '$' + (data.potential_savings || 0).toFixed(2));
    setText('dashRegionsCount', (data.analyzed_regions || []).length);
    setText('dashTimestamp', data.timestamp ? new Date(data.timestamp).toLocaleString() : '');

    var resources = data.resources || [];

    // Populate finding type tab counts and KPIs
    var costRes = resources.filter(function(r) { return (r.finding_type || 'COST') === 'COST'; });
    var secRes = resources.filter(function(r) { return r.finding_type === 'SECURITY'; });
    var govRes = resources.filter(function(r) { return r.finding_type === 'GOVERNANCE'; });

    setText('tabCountAll', resources.length);
    setText('tabCountCost', costRes.length);
    setText('tabCountSecurity', secRes.length);
    setText('tabCountGovernance', govRes.length);

    // Cost tab KPIs
    var costSpend = costRes.reduce(function(sum, r) { return sum + (r.monthly_cost_usd || 0); }, 0);
    var costIdle = costRes.filter(function(r) { return isActionableJs(r); }).length;
    var costSavings = costRes.filter(function(r) { return isActionableJs(r); }).reduce(function(sum, r) { return sum + (r.monthly_cost_usd || 0); }, 0);
    setText('dashCostTotal', costRes.length);
    setText('dashCostSpend', '$' + costSpend.toFixed(2));
    setText('dashCostIdle', costIdle);
    setText('dashCostSavings', '$' + costSavings.toFixed(2));

    // Security tab KPIs
    var secActionable = secRes.filter(function(r) { return isActionableJs(r); }).length;
    var secTypes = {};
    secRes.forEach(function(r) { secTypes[r.resource_type || 'Unknown'] = true; });
    var secRegions = {};
    secRes.forEach(function(r) { if (r.region) secRegions[r.region] = true; });
    setText('dashSecTotal', secRes.length);
    setText('dashSecActionable', secActionable);
    setText('dashSecTypes', Object.keys(secTypes).length);
    setText('dashSecRegions', Object.keys(secRegions).length);

    // Governance tab KPIs
    var govActionable = govRes.filter(function(r) { return isActionableJs(r); }).length;
    var govTypes = {};
    govRes.forEach(function(r) { govTypes[r.resource_type || 'Unknown'] = true; });
    var govRegions = {};
    govRes.forEach(function(r) { if (r.region) govRegions[r.region] = true; });
    setText('dashGovTotal', govRes.length);
    setText('dashGovActionable', govActionable);
    setText('dashGovTypes', Object.keys(govTypes).length);
    setText('dashGovRegions', Object.keys(govRegions).length);

    var typeCounts = {};
    resources.forEach(function(r) {
        var t = r.resource_type || 'Unknown';
        typeCounts[t] = (typeCounts[t] || 0) + 1;
    });

    var breakdownContainer = document.getElementById('resourceBreakdown');
    if (breakdownContainer) {
        breakdownContainer.innerHTML = '';
        var typeColorMap = getTypeColors();
        var sortedTypes = Object.keys(typeCounts).sort(function(a, b) { return typeCounts[b] - typeCounts[a]; });
        sortedTypes.forEach(function(type) {
            var color = typeColorMap[type] || '#6b7280';
            var encodedType = encodeURIComponent(type);
            var card = document.createElement('div');
            card.className = 'col-auto';
            card.innerHTML =
                '<a href="/dashboard/resources?type=' + encodedType + '" class="text-decoration-none">' +
                '<div class="card text-center border-0 shadow-sm dashboard-card" style="border-left: 3px solid ' + color + ' !important; cursor: pointer; min-width: 100px;">' +
                '<div class="card-body py-3 px-3">' +
                '<h6 class="card-subtitle text-uppercase small fw-semibold mb-1" style="color: var(--text-muted); letter-spacing: 0.05em; font-size: 0.7rem;">' + type + '</h6>' +
                '<h4 class="mb-0 fw-bold" style="color: var(--text-primary);">' + typeCounts[type] + '</h4>' +
                '</div></div></a>';
            breakdownContainer.appendChild(card);
        });
        if (sortedTypes.length === 0) {
            breakdownContainer.innerHTML = '<div class="col"><p class="text-muted mb-0">No resources found</p></div>';
        }
    }
}

function setText(id, value) {
    var el = document.getElementById(id);
    if (el) el.textContent = value;
}

/** Renders server-computed narrative text with \n and "- " bullets as structured HTML. */
function setNarrativeHtml(id, value) {
    var el = document.getElementById(id);
    if (!el || !value) return;
    var parts = value.split('\n').filter(function(p) { return p.trim(); });
    var html = '';
    var inList = false;
    parts.forEach(function(part) {
        var trimmed = part.trim();
        if (trimmed.startsWith('- ')) {
            if (!inList) { html += '<ul class="mb-2 ps-3" style="list-style-type: disc;">'; inList = true; }
            html += '<li style="margin-bottom: 0.15rem;">' + esc(trimmed.substring(2)) + '</li>';
        } else {
            if (inList) { html += '</ul>'; inList = false; }
            html += '<p class="mb-2">' + esc(trimmed) + '</p>';
        }
    });
    if (inList) html += '</ul>';
    el.innerHTML = html;
}

function initResourceFilters() {
    var search = document.getElementById('resourceSearch');
    var typeFilter = document.getElementById('resourceTypeFilter');
    var recFilter = document.getElementById('resourceRecommendationFilter');
    var regionFilter = document.getElementById('resourceRegionFilter');
    var ftFilter = document.getElementById('findingTypeFilter');
    var clearBtn = document.getElementById('clearFiltersBtn');

    if (!search && !typeFilter && !recFilter && !regionFilter) return;

    window._applyResourceFilters = applyFilters;

    function applyFilters() {
        var resources = window._sentinelResources || [];
        var s = search ? search.value.toLowerCase() : '';
        var t = typeFilter ? typeFilter.value : 'all';
        var r = recFilter ? recFilter.value : 'all';
        var reg = regionFilter ? regionFilter.value : 'all';
        var ft = ftFilter ? ftFilter.value : 'all';

        var filtered = resources.filter(function(res) {
            var matchSearch = !s || (res.resource_name || '').toLowerCase().includes(s) || (res.resource_id || '').toLowerCase().includes(s);
            var matchType = t === 'all' || (res.resource_type || '').includes(t);
            var matchRegion = reg === 'all' || res.region === reg;
            var matchFinding = ft === 'all' || (res.finding_type || 'COST') === ft;
            var matchRec;
            if (r === 'all') {
                matchRec = true;
            } else if (r === 'actionable') {
                matchRec = isActionableJs(res);
            } else if (r === 'savings') {
                // Potential savings: actionable resources with cost > 0
                var rec = (res.recommendation || '');
                matchRec = (res.monthly_cost_usd > 0) &&
                    (rec.includes('Idle') || rec.includes('Terminat') || rec.includes('Delete') ||
                     rec.includes('Release') || rec.includes('Unused') || rec.includes('Empty') ||
                     rec.includes('Inactive') || rec.includes('Downsiz'));
            } else {
                matchRec = (res.recommendation || '').toLowerCase().includes(r.toLowerCase());
            }
            return matchSearch && matchType && matchRec && matchRegion && matchFinding;
        });

        renderResourceTable(filtered);
    }

    if (search) search.addEventListener('input', applyFilters);
    if (typeFilter) typeFilter.addEventListener('change', applyFilters);
    if (recFilter) recFilter.addEventListener('change', applyFilters);
    if (regionFilter) regionFilter.addEventListener('change', applyFilters);
    if (ftFilter) ftFilter.addEventListener('change', applyFilters);
    if (clearBtn) clearBtn.addEventListener('click', function() {
        if (search) search.value = '';
        if (typeFilter) typeFilter.value = 'all';
        if (recFilter) recFilter.value = 'all';
        if (regionFilter) regionFilter.value = 'all';
        if (ftFilter) ftFilter.value = 'all';
        window._sortField = null;
        window._sortAsc = true;
        var costIcon = document.getElementById('sortCostIcon');
        var cpuIcon = document.getElementById('sortCpuIcon');
        var createdIcon = document.getElementById('sortCreatedIcon');
        var sevIcon = document.getElementById('sortSeverityIcon');
        if (costIcon) costIcon.textContent = '';
        if (cpuIcon) cpuIcon.textContent = '';
        if (createdIcon) createdIcon.textContent = '';
        if (sevIcon) sevIcon.textContent = '';
        applyFilters();
    });
}

window._sortField = null;
window._sortAsc = true;

function sortResourceTable(field) {
    if (window._sortField === field) {
        window._sortAsc = !window._sortAsc;
    } else {
        window._sortField = field;
        window._sortAsc = false; // Default descending (highest first)
    }

    var costIcon = document.getElementById('sortCostIcon');
    var cpuIcon = document.getElementById('sortCpuIcon');
    var createdIcon = document.getElementById('sortCreatedIcon');
    var severityIcon = document.getElementById('sortSeverityIcon');
    if (costIcon) costIcon.textContent = field === 'cost' ? (window._sortAsc ? '\u25B2' : '\u25BC') : '';
    if (cpuIcon) cpuIcon.textContent = field === 'cpu' ? (window._sortAsc ? '\u25B2' : '\u25BC') : '';
    if (createdIcon) createdIcon.textContent = field === 'created' ? (window._sortAsc ? '\u25B2' : '\u25BC') : '';
    if (severityIcon) severityIcon.textContent = field === 'severity' ? (window._sortAsc ? '\u25B2' : '\u25BC') : '';

    // Get currently displayed (filtered) resources by re-filtering, then sorting
    var resources = window._sentinelResources || [];
    var search = document.getElementById('resourceSearch');
    var typeFilter = document.getElementById('resourceTypeFilter');
    var recFilter = document.getElementById('resourceRecommendationFilter');
    var regionFilter = document.getElementById('resourceRegionFilter');

    var s = search ? search.value.toLowerCase() : '';
    var t = typeFilter ? typeFilter.value : 'all';
    var r = recFilter ? recFilter.value : 'all';
    var reg = regionFilter ? regionFilter.value : 'all';

    var filtered = resources.filter(function(res) {
        var matchSearch = !s || (res.resource_name || '').toLowerCase().includes(s) || (res.resource_id || '').toLowerCase().includes(s);
        var matchType = t === 'all' || (res.resource_type || '').includes(t);
        var matchRegion = reg === 'all' || res.region === reg;
        var matchRec;
        if (r === 'all') {
            matchRec = true;
        } else if (r === 'savings') {
            var rec = (res.recommendation || '');
            matchRec = (res.monthly_cost_usd > 0) &&
                (rec.includes('Idle') || rec.includes('Terminat') || rec.includes('Delete') ||
                 rec.includes('Release') || rec.includes('Unused') || rec.includes('Empty') ||
                 rec.includes('Inactive') || rec.includes('Downsiz'));
        } else {
            matchRec = (res.recommendation || '').toLowerCase().includes(r.toLowerCase());
        }
        return matchSearch && matchType && matchRec && matchRegion;
    });

    var asc = window._sortAsc;
    var sevOrder = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1, INFO: 0 };
    if (field === 'severity') {
        filtered.sort(function(a, b) {
            var va = sevOrder[a.severity || 'INFO'] || 0;
            var vb = sevOrder[b.severity || 'INFO'] || 0;
            return asc ? va - vb : vb - va;
        });
    } else if (field === 'created') {
        filtered.sort(function(a, b) {
            var da = a.created_date ? new Date(a.created_date).getTime() : 0;
            var db = b.created_date ? new Date(b.created_date).getTime() : 0;
            return asc ? da - db : db - da;
        });
    } else {
        var key = field === 'cost' ? 'monthly_cost_usd' : 'cpu_utilization_avg';
        filtered.sort(function(a, b) {
            var va = a[key] || 0;
            var vb = b[key] || 0;
            return asc ? va - vb : vb - va;
        });
    }

    renderResourceTable(filtered);
}

function renderResourceTable(resources) {
    var tbody = document.getElementById('resourceTableBody');
    if (!tbody) return;

    tbody.innerHTML = '';

    if (resources.length === 0) {
        tbody.innerHTML = '<tr><td colspan="11" class="text-center text-muted py-4">No resources found</td></tr>';
        return;
    }

    resources.forEach(function(r) {
        var tr = document.createElement('tr');
        var badgeClass = getBadgeClass(r.recommendation || '');
        var ft = r.finding_type || 'COST';
        var ftBadge = ft === 'SECURITY' ? '<span class="badge" style="background:#f56565;font-size:0.65rem;">Security</span>'
            : ft === 'GOVERNANCE' ? '<span class="badge" style="background:#667eea;font-size:0.65rem;">Governance</span>'
            : '<span class="badge" style="background:#ed8936;font-size:0.65rem;">Cost</span>';
        var sev = r.severity || 'INFO';
        var sevColor = sev === 'CRITICAL' ? '#dc2626' : sev === 'HIGH' ? '#ea580c' : sev === 'MEDIUM' ? '#d97706' : sev === 'LOW' ? '#65a30d' : '#9ca3af';
        var sevBadge = '<span class="badge" style="background:' + sevColor + ';font-size:0.65rem;">' + sev + '</span>';
        tr.innerHTML =
            '<td>' + esc(r.region) + '</td>' +
            '<td><span class="badge bg-secondary">' + esc(r.resource_type) + '</span></td>' +
            '<td>' + ftBadge + '</td>' +
            '<td>' + sevBadge + '</td>' +
            '<td>' + esc(r.resource_name) + '</td>' +
            '<td><code>' + esc(r.resource_id) + '</code></td>' +
            '<td>' + esc(r.state) + '</td>' +
            '<td>$' + (r.monthly_cost_usd || 0).toFixed(2) + '</td>' +
            '<td>' + (r.cpu_utilization_avg || 0).toFixed(1) + '%</td>' +
            '<td></td>' +
            '<td>' + (r.created_date ? new Date(r.created_date).toLocaleDateString() : '-') + '</td>';

        // Build recommendation badge via DOM to safely handle tooltip title
        var recTd = tr.cells[9];
        var badge = document.createElement('span');
        badge.className = 'badge ' + badgeClass;
        badge.textContent = r.recommendation || '';

        var tipText = r.recommendation_detail || buildFallbackTip(r);
        if (tipText) {
            badge.setAttribute('data-bs-toggle', 'tooltip');
            badge.setAttribute('data-bs-placement', 'top');
            badge.setAttribute('data-bs-html', 'false');
            badge.setAttribute('title', tipText.replace(/ \| /g, ' — '));
            badge.style.cursor = 'help';
        }
        recTd.appendChild(badge);

        tbody.appendChild(tr);
    });

    // Initialize Bootstrap tooltips on recommendation badges
    var tooltipTriggerList = tbody.querySelectorAll('[data-bs-toggle="tooltip"]');
    tooltipTriggerList.forEach(function(el) {
        new bootstrap.Tooltip(el);
    });
}

function buildFallbackTip(r) {
    var parts = [];
    var rec = r.recommendation || '';
    var type = r.resource_type || '';
    var cost = r.monthly_cost_usd || 0;
    var cpu = r.cpu_utilization_avg || 0;
    var state = r.state || '';

    // Metrics summary
    if (cpu > 0 || (type === 'EC2' || type === 'RDS' || type === 'ElastiCache' || type === 'Aurora' || type === 'Redshift')) {
        parts.push('CPU: ' + cpu.toFixed(1) + '% (7-day avg)');
    }
    if (cost > 0) parts.push('Cost: $' + cost.toFixed(2) + '/mo');
    if (state) parts.push('State: ' + state);
    if (r.instance_type) parts.push('Type: ' + r.instance_type);

    // AI detail
    if (r.recommendation_detail) {
        parts.push(r.recommendation_detail);
    }

    return parts.length > 0 ? parts.join(' | ') : '';
}

function isActionableJs(r) {
    var rec = r.recommendation || '';
    if (!rec) return false;
    // Cost prefixes
    if (rec.indexOf('Idle') === 0 || rec.indexOf('Consider Terminating') === 0
        || rec.indexOf('Delete') === 0 || rec.indexOf('Release') === 0
        || rec.indexOf('Unused') === 0 || rec.indexOf('Empty') === 0
        || rec.indexOf('Inactive') === 0 || rec.indexOf('Stopped') === 0) return true;
    // Security/governance prefixes
    return rec.indexOf('Rotate') === 0 || rec.indexOf('Enable') === 0
        || rec.indexOf('Restrict') === 0 || rec.indexOf('Expired') === 0
        || rec.indexOf('Exposed') === 0 || rec.indexOf('Missing') === 0
        || rec.indexOf('Stale') === 0 || rec.indexOf('Misconfigured') === 0;
}

function getBadgeClass(rec) {
    if (rec.includes('Idle') || rec.includes('Terminat') || rec.includes('Delete') || rec.includes('Release') || rec.includes('Empty')) return 'bg-danger';
    if (rec.includes('Expired') || rec.includes('Exposed') || rec.includes('Misconfigured')) return 'bg-danger';
    if (rec.includes('Low') || rec.includes('Inactive')) return 'bg-warning text-dark';
    if (rec.includes('Rotate') || rec.includes('Enable') || rec.includes('Restrict') || rec.includes('Missing') || rec.includes('Stale')) return 'bg-warning text-dark';
    if (rec.includes('Moderate') || rec.includes('Review')) return 'bg-info';
    if (rec.includes('Active') || rec.includes('In Use')) return 'bg-success';
    if (rec.includes('Stopped')) return 'bg-secondary';
    return 'bg-secondary';
}

function esc(str) {
    if (!str) return '';
    var div = document.createElement('div');
    div.appendChild(document.createTextNode(str));
    return div.innerHTML;
}

function initExportCsv() {
    var btn = document.getElementById('exportCsvBtn');
    if (!btn) return;

    btn.addEventListener('click', function() {
        var resources = window._sentinelResources;
        if (!resources || resources.length === 0) { showToast('No resources to export'); return; }

        btn.disabled = true;
        fetch('/export/csv', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ resources: resources })
        })
        .then(function(r) { if (!r.ok) throw new Error('Export failed'); return r.blob(); })
        .then(function(blob) {
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            var report = JSON.parse(sessionStorage.getItem('sentinel-report') || '{}');
            var profile = (report.profile_name || 'unknown').replace(/[^a-zA-Z0-9_-]/g, '_');
            var ts = new Date().toISOString().replace(/[:.]/g, '-').split('T');
            a.download = profile + '_' + ts[0] + '_' + ts[1].substring(0, 8) + '.csv';
            a.click();
            URL.revokeObjectURL(url);
            showToast('CSV exported');
        })
        .catch(function(err) { showToast('Export failed: ' + err.message); })
        .finally(function() { btn.disabled = false; });
    });
}

var _chartInstances = {};

function renderCharts(resources) {
    if (!resources || resources.length === 0 || typeof Chart === 'undefined') return;

    var section = document.getElementById('chartsSection');
    if (section) section.style.display = '';

    var typeColors = getTypeColors();

    var typeCounts = {};
    var typeCosts = {};
    var recCounts = {};
    var regionCounts = {};

    resources.forEach(function(r) {
        var t = r.resource_type || 'Other';
        var shortType = t.replace(' Instance', '').replace(' Volume', '').replace(' Cluster', '').replace(' Table', '').replace(' Bucket', '').replace('APPLICATION ', '').replace('NETWORK ', '');
        typeCounts[shortType] = (typeCounts[shortType] || 0) + 1;
        typeCosts[shortType] = (typeCosts[shortType] || 0) + (r.monthly_cost_usd || 0);

        var rec = r.recommendation || 'Unknown';
        if (rec.includes('Idle') || rec.includes('Delete') || rec.includes('Release') || rec.includes('Empty')) rec = 'Idle / Remove';
        else if (rec.includes('Low')) rec = 'Low Utilisation';
        else if (rec.includes('Moderate') || rec.includes('Review')) rec = 'Review';
        else if (rec.includes('Active') || rec.includes('In Use')) rec = 'Active';
        else if (rec.includes('Stopped')) rec = 'Stopped';
        recCounts[rec] = (recCounts[rec] || 0) + 1;

        var region = r.region || 'Unknown';
        regionCounts[region] = (regionCounts[region] || 0) + 1;
    });

    var typeLabels = Object.keys(typeCounts);
    var typeData = typeLabels.map(function(k) { return typeCounts[k]; });
    var typeChartColors = typeLabels.map(function(k) {
        for (var key in typeColors) { if (k.includes(key)) return typeColors[key]; }
        return '#94a3b8';
    });

    renderDonut('resourceDonutChart', typeLabels, typeData, typeChartColors);

    var costLabels = Object.keys(typeCosts).filter(function(k) { return typeCosts[k] > 0; });
    var costData = costLabels.map(function(k) { return Math.round(typeCosts[k] * 100) / 100; });
    var costColors = costLabels.map(function(k) {
        for (var key in typeColors) { if (k.includes(key)) return typeColors[key]; }
        return '#94a3b8';
    });
    renderBar('costBarChart', costLabels, costData, costColors, 'Monthly Cost ($)');

    var recLabels = Object.keys(recCounts);
    var recData = recLabels.map(function(k) { return recCounts[k]; });
    var recColors = recLabels.map(function(k) {
        if (k.includes('Idle')) return '#ef4444';
        if (k.includes('Low')) return '#f59e0b';
        if (k.includes('Review')) return '#3b82f6';
        if (k.includes('Active')) return '#10b981';
        if (k.includes('Stopped')) return '#6b7280';
        return '#94a3b8';
    });
    renderDonut('recommendationDonutChart', recLabels, recData, recColors);

    var regionLabels = Object.keys(regionCounts).sort(function(a, b) { return regionCounts[b] - regionCounts[a]; }).slice(0, 10);
    var regionData = regionLabels.map(function(k) { return regionCounts[k]; });
    renderBar('regionBarChart', regionLabels, regionData, regionLabels.map(function() { return '#2563eb'; }), 'Resources');
}

function renderDonut(canvasId, labels, data, colors) {
    var canvas = document.getElementById(canvasId);
    if (!canvas) return;
    if (_chartInstances[canvasId]) _chartInstances[canvasId].destroy();

    _chartInstances[canvasId] = new Chart(canvas, {
        type: 'doughnut',
        data: {
            labels: labels,
            datasets: [{ data: data, backgroundColor: colors, borderWidth: 0, hoverOffset: 6 }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            cutout: '65%',
            plugins: {
                legend: { position: 'right', labels: { padding: 12, usePointStyle: true, pointStyle: 'circle', font: { size: 11 } } }
            }
        }
    });
}

function renderBar(canvasId, labels, data, colors, yLabel) {
    var canvas = document.getElementById(canvasId);
    if (!canvas) return;
    if (_chartInstances[canvasId]) _chartInstances[canvasId].destroy();

    _chartInstances[canvasId] = new Chart(canvas, {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [{ data: data, backgroundColor: colors, borderRadius: 6, barThickness: 28 }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: { legend: { display: false } },
            scales: {
                y: { beginAtZero: true, title: { display: true, text: yLabel, font: { size: 11 } }, grid: { color: 'rgba(0,0,0,0.05)' } },
                x: { grid: { display: false }, ticks: { font: { size: 10 } } }
            }
        }
    });
}

function populateCorrelations(resources) {
    var section = document.getElementById('correlationsSection');
    var list = document.getElementById('correlationsList');
    var countBadge = document.getElementById('correlationCount');
    if (!section || !list || !resources || resources.length === 0) return;

    // Find resources with correlation details (set by ResourceCorrelationEngine)
    // Exclude generic AI fallback messages
    var correlated = resources.filter(function(r) {
        var detail = r.recommendation_detail || '';
        return detail.length > 0
            && !detail.startsWith('AI reviewed')
            && (detail.indexOf('attached to') >= 0 || detail.indexOf('associated with') >= 0
                || detail.indexOf('no running') >= 0 || detail.indexOf('no longer exists') >= 0
                || detail.indexOf('orphaned') >= 0 || detail.indexOf('idle') >= 0
                || detail.indexOf('Idle') >= 0 || detail.indexOf('deleted') >= 0
                || detail.indexOf('no EC2') >= 0 || detail.indexOf('no compute') >= 0
                || detail.indexOf('Unattached') >= 0 || detail.indexOf('zero healthy') >= 0
                || detail.indexOf('abandoned') >= 0 || detail.indexOf('no active') >= 0
                || detail.indexOf('forgotten') >= 0 || detail.indexOf('Source database') >= 0
                || detail.indexOf('Unused') >= 0);
    });

    if (correlated.length === 0) return;

    section.style.display = '';
    if (countBadge) countBadge.textContent = correlated.length + ' found';

    // Group by correlation type
    var groups = {};
    correlated.forEach(function(r) {
        var detail = r.recommendation_detail || '';
        var category;
        if (detail.indexOf('attached to') >= 0 || detail.indexOf('Attached to Stopped') >= 0) category = 'Volumes on Stopped Instances';
        else if (detail.indexOf('associated with') >= 0 || detail.indexOf('Instance is Stopped') >= 0) category = 'EIPs on Stopped Instances';
        else if (detail.indexOf('no running') >= 0 || detail.indexOf('no compute') >= 0 || detail.indexOf('No Running') >= 0) category = 'Resources in Empty Regions';
        else if (detail.indexOf('no longer exists') >= 0 || detail.indexOf('Source database') >= 0) category = 'Orphaned Resources';
        else if (detail.indexOf('orphaned') >= 0 || detail.indexOf('no EC2') >= 0) category = 'Orphaned Snapshots';
        else if (detail.indexOf('zero healthy') >= 0) category = 'Load Balancers Without Targets';
        else if (detail.indexOf('idle') >= 0 || detail.indexOf('Idle') >= 0 || detail.indexOf('abandoned') >= 0) category = 'Idle Dependencies';
        else if (detail.indexOf('Unused') >= 0 || detail.indexOf('forgotten') >= 0) category = 'Unused Configuration';
        else category = 'Other Correlations';

        if (!groups[category]) groups[category] = [];
        groups[category].push(r);
    });

    var categoryColors = {
        'Volumes on Stopped Instances': 'danger',
        'EIPs on Stopped Instances': 'danger',
        'Resources in Empty Regions': 'warning',
        'Orphaned Resources': 'warning',
        'Orphaned Snapshots': 'warning',
        'Load Balancers Without Targets': 'info',
        'Idle Dependencies': 'info',
        'Unused Configuration': 'secondary',
        'Other Correlations': 'secondary'
    };

    var categoryIcons = {
        'Volumes on Stopped Instances': '&#x1f4be;',
        'EIPs on Stopped Instances': '&#x1f310;',
        'Resources in Empty Regions': '&#x1f30d;',
        'Orphaned Resources': '&#x1f47b;',
        'Orphaned Snapshots': '&#x1f4f8;',
        'Load Balancers Without Targets': '&#x2696;',
        'Idle Dependencies': '&#x1f517;',
        'Unused Configuration': '&#x2699;',
        'Other Correlations': '&#x1f50d;'
    };

    list.innerHTML = '';
    var totalSavings = 0;

    Object.keys(groups).forEach(function(category) {
        var items = groups[category];
        var color = categoryColors[category] || 'secondary';
        var icon = categoryIcons[category] || '&#x1f517;';
        var groupSavings = 0;
        items.forEach(function(r) { groupSavings += (r.monthly_cost_usd || 0); });
        totalSavings += groupSavings;

        var groupDiv = document.createElement('div');
        groupDiv.className = 'border-bottom';
        groupDiv.style.cssText = 'border-color: var(--border-color) !important;';

        var header = '<div class="d-flex align-items-center justify-content-between px-3 py-2" style="background: var(--bg-secondary);">' +
            '<div class="d-flex align-items-center gap-2">' +
            '<span>' + icon + '</span>' +
            '<span class="fw-semibold" style="color: var(--text-primary); font-size: 0.85rem;">' + category + '</span>' +
            '<span class="badge bg-' + color + '" style="font-size: 0.6rem;">' + items.length + '</span>' +
            '</div>' +
            (groupSavings > 0 ? '<span class="fw-semibold small" style="color: var(--accent-danger);">$' + groupSavings.toFixed(2) + '/mo</span>' : '') +
            '</div>';

        var rows = items.map(function(r) {
            return '<div class="d-flex align-items-start gap-3 px-3 py-2" style="border-top: 1px solid var(--border-color);">' +
                '<div style="min-width: 90px;">' +
                '<span class="badge bg-secondary" style="font-size: 0.65rem;">' + esc(r.resource_type) + '</span>' +
                '</div>' +
                '<div class="flex-grow-1">' +
                '<div class="d-flex align-items-center gap-2 mb-1">' +
                '<code style="font-size: 0.78rem;">' + esc(r.resource_id) + '</code>' +
                (r.region ? '<small class="text-muted">' + esc(r.region) + '</small>' : '') +
                (r.monthly_cost_usd > 0 ? '<small class="fw-semibold" style="color: var(--accent-danger);">$' + r.monthly_cost_usd.toFixed(2) + '/mo</small>' : '') +
                '</div>' +
                '<p class="mb-0 small" style="color: var(--text-secondary); line-height: 1.5;">' + esc(r.recommendation_detail).replace(/ \| /g, '<br>') + '</p>' +
                '</div></div>';
        }).join('');

        groupDiv.innerHTML = header + rows;
        list.appendChild(groupDiv);
    });

    // Update count badge with savings
    if (countBadge) {
        countBadge.textContent = correlated.length + ' found' + (totalSavings > 0 ? ' \u2014 $' + totalSavings.toFixed(2) + '/mo at risk' : '');
    }
}

function populateAiInsights(insights) {
    var section = document.getElementById('aiInsightsSection');
    if (!section) return;
    if (!insights || !insights.executive_summary) {
        section.style.display = 'none';
        return;
    }

    section.style.display = '';

    // Collapse AI section on failure, expand on success
    var collapseEl = document.getElementById('collapseAi');
    if (collapseEl) {
        var isFailed = (insights.executive_summary || '').indexOf('failed') >= 0
            || (insights.executive_summary || '').indexOf('error') >= 0;
        if (isFailed) {
            collapseEl.classList.remove('show');
            var toggle = document.querySelector('[data-bs-target="#collapseAi"]');
            if (toggle) toggle.setAttribute('aria-expanded', 'false');
        } else {
            collapseEl.classList.add('show');
        }
    }

    setText('aiProviderBadge', (insights.provider || 'AI').toUpperCase() + ' - ' + (insights.model || ''));
    var summary = insights.executive_summary || '';
    // If the summary contains raw JSON (graceful degradation from parse failure), try to extract the actual text
    if (summary.trim().startsWith('{') && summary.indexOf('"executive_summary"') >= 0) {
        try {
            var parsed = JSON.parse(summary);
            summary = parsed.executive_summary || summary;
        } catch(e) {
            // Not valid JSON — extract the value after "executive_summary": "
            var match = summary.match(/"executive_summary"\s*:\s*"([^"]+)"/);
            if (match) summary = match[1];
        }
    }
    setNarrativeHtml('aiExecutiveSummary', summary);
    setNarrativeHtml('aiCostNarrative', insights.cost_narrative || 'No cost analysis available');
    setNarrativeHtml('aiRiskOverview', insights.risk_overview || 'No risk assessment available');

    var actions = insights.prioritized_actions || [];
    var actionsCard = document.getElementById('aiActionsCard');
    var actionsBody = document.getElementById('aiActionsBody');
    if (actions.length > 0 && actionsCard && actionsBody) {
        actionsCard.style.display = '';
        actionsBody.innerHTML = '';
        actions.forEach(function(a) {
            var riskBadge = a.risk === 'SAFE' ? 'bg-success' : a.risk === 'LOW' ? 'bg-info' : a.risk === 'MEDIUM' ? 'bg-warning text-dark' : 'bg-danger';
            var actionBadge = a.action === 'TERMINATE' ? 'bg-danger' : a.action === 'DOWNSIZE' ? 'bg-warning text-dark' : a.action === 'KEEP' ? 'bg-success' : 'bg-info';
            var tr = document.createElement('tr');
            tr.innerHTML =
                '<td><code>' + esc(a.resource_id) + '</code></td>' +
                '<td>' + esc(a.resource_type) + '</td>' +
                '<td><span class="badge ' + actionBadge + '">' + esc(a.action) + '</span></td>' +
                '<td><span class="badge ' + riskBadge + '">' + esc(a.risk) + '</span></td>' +
                '<td>$' + (a.estimated_savings || 0).toFixed(2) + '</td>' +
                '<td>' + esc(a.reasoning) + '</td>';
            actionsBody.appendChild(tr);
        });
    }

    var rightSizing = insights.right_sizing || [];
    var rsCard = document.getElementById('aiRightSizingCard');
    var rsBody = document.getElementById('aiRightSizingBody');
    if (rightSizing.length > 0 && rsCard && rsBody) {
        rsCard.style.display = '';
        rsBody.innerHTML = '';
        rightSizing.forEach(function(rs) {
            var tr = document.createElement('tr');
            tr.innerHTML =
                '<td><code>' + esc(rs.resource_id) + '</code></td>' +
                '<td>' + esc(rs.current_type) + '</td>' +
                '<td><strong>' + esc(rs.recommended_type) + '</strong></td>' +
                '<td>$' + (rs.current_cost || 0).toFixed(2) + '</td>' +
                '<td class="text-success">$' + (rs.projected_cost || 0).toFixed(2) + '</td>' +
                '<td>' + esc(rs.reasoning) + '</td>';
            rsBody.appendChild(tr);
        });
    }

    var arch = insights.architecture_insights || [];
    var archCard = document.getElementById('aiArchCard');
    var archBody = document.getElementById('aiArchBody');
    if (arch.length > 0 && archCard && archBody) {
        archCard.style.display = '';
        archBody.innerHTML = '';
        arch.forEach(function(a) {
            var catBadge = a.category === 'WASTE' ? 'bg-danger' : a.category === 'SECURITY' ? 'bg-warning text-dark' : a.category === 'REDUNDANCY' ? 'bg-info' : 'bg-primary';
            var div = document.createElement('div');
            div.className = 'mb-3 p-3 rounded border';
            div.innerHTML =
                '<span class="badge ' + catBadge + ' mb-2">' + esc(a.category) + '</span>' +
                '<p class="mb-1"><strong>' + esc(a.finding) + '</strong></p>' +
                '<p class="mb-0 text-muted small">' + esc(a.recommendation) + '</p>';
            archBody.appendChild(div);
        });
    }

    // AI Usage stats
    populateAiUsage(insights.ai_usage);
}

function populateAiUsage(usage) {
    var card = document.getElementById('aiUsageCard');
    if (!card || !usage) return;

    card.style.display = '';

    var statsEl = document.getElementById('aiUsageStats');
    var extraEl = document.getElementById('aiUsageExtraStats');
    if (!statsEl || !extraEl) return;

    function formatDuration(ms) {
        if (ms < 1000) return ms + 'ms';
        var secs = ms / 1000;
        if (secs < 60) return secs.toFixed(1) + 's';
        var mins = Math.floor(secs / 60);
        var remainSecs = (secs % 60).toFixed(0);
        return mins + 'm ' + remainSecs + 's';
    }

    function formatNumber(n) {
        if (n == null) return '--';
        return n.toLocaleString();
    }

    function statTile(label, value, sublabel) {
        return '<div class="col-6 col-md-3">' +
            '<div class="p-2 rounded-3" style="background: var(--bg-secondary);">' +
            '<div class="text-uppercase fw-semibold mb-1" style="font-size: 0.65rem; letter-spacing: 0.06em; color: var(--text-muted);">' + label + '</div>' +
            '<div class="fw-bold" style="font-size: 1.05rem; color: var(--text-primary);">' + value + '</div>' +
            (sublabel ? '<div style="font-size: 0.7rem; color: var(--text-muted);">' + sublabel + '</div>' : '') +
            '</div></div>';
    }

    // Primary stats row
    var tps = usage.tokens_per_second || 0;
    var tpsLabel = tps > 80 ? 'blazing' : tps > 40 ? 'fast' : tps > 15 ? 'steady' : tps > 0 ? 'thoughtful' : '';
    statsEl.innerHTML =
        statTile('Thinking Time', formatDuration(usage.duration_ms || 0)) +
        statTile('Tokens Used', formatNumber(usage.total_tokens)) +
        statTile('Speed', tps.toFixed(1) + ' tok/s', tpsLabel) +
        statTile('Model', esc(usage.model || '--'));

    // Extra detail stats (collapsed by default)
    var costStr = usage.estimated_cost_usd != null && usage.estimated_cost_usd > 0
        ? '$' + usage.estimated_cost_usd.toFixed(4) : 'Free (local)';
    extraEl.innerHTML =
        statTile('Prompt Tokens', formatNumber(usage.prompt_tokens)) +
        statTile('Completion Tokens', formatNumber(usage.completion_tokens)) +
        statTile('Prompt Size', formatNumber(usage.prompt_characters) + ' chars') +
        statTile('Response Size', formatNumber(usage.response_characters) + ' chars') +
        statTile('AI Cost', costStr);

    // Fun fact
    var funFactEl = document.getElementById('aiFunFact');
    var funFactText = document.getElementById('aiFunFactText');
    if (usage.fun_fact && funFactEl && funFactText) {
        funFactEl.style.display = '';
        funFactText.textContent = usage.fun_fact;
    }
}

function updateSidebarJobBadge() {
    fetch('/analyse/jobs')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var badge = document.getElementById('sidebarJobBadge');
            if (!badge) return;
            var active = data.active_count || 0;
            if (active > 0) {
                badge.textContent = active;
                badge.style.display = '';
            } else {
                badge.style.display = 'none';
            }
        })
        .catch(function() {});
}

function scanAllProfiles() {
    // Collect regions
    var regions = [];
    document.querySelectorAll('.region-checkbox:checked').forEach(function(cb) { regions.push(cb.value); });
    if (regions.length === 0) {
        showToast('Please select at least one region');
        return;
    }

    // Collect options
    var aiCheckbox = document.getElementById('enableAiFiltering');
    var enableAi = aiCheckbox ? aiCheckbox.checked : false;
    var selectedProviderRadio = document.querySelector('input[name="aiProvider"]:checked');
    var aiProvider = selectedProviderRadio ? selectedProviderRadio.value : 'bedrock';
    var aiModelSelect = document.getElementById('aiModelSelect');
    var aiModel = (aiModelSelect && aiModelSelect.value) ? aiModelSelect.value : null;
    var scanCategoryRadio = document.querySelector('input[name="scanCategory"]:checked');
    var scanCategory = scanCategoryRadio ? scanCategoryRadio.value : 'FULL';

    var btn = document.getElementById('scanAllBtn');
    var spinner = document.getElementById('scanAllSpinner');
    if (btn) btn.disabled = true;
    if (spinner) spinner.style.display = '';
    showProgress('Submitting batch scan...', 0);
    window.scrollTo({ top: 0, behavior: 'smooth' });

    // Get all profiles, build batch request
    fetch('/profiles')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var profiles = (data.profiles || []).filter(function(p) {
                var lower = p.toLowerCase();
                return !lower.startsWith('demo') && !lower.startsWith('mock');
            });
            if (profiles.length === 0) {
                showToast('No AWS profiles found');
                return;
            }

            var batch = profiles.map(function(profile) {
                var req = {
                    profile_name: profile,
                    regions: regions,
                    enable_ai_filter: enableAi,
                    ai_provider: aiProvider,
                    scan_category: scanCategory
                };
                if (aiModel) req.ai_model = aiModel;
                return req;
            });

            return fetch('/analyse/batch', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(batch)
            }).then(function(r) {
                if (!r.ok) {
                    return r.json().then(function(err) {
                        showToast(err.detail || 'Batch scan rejected (status ' + r.status + ')', 'error');
                        hideProgress();
                        return null;
                    });
                }
                return r.json();
            });
        })
        .then(function(result) {
            if (result) {
                var failedList = result.failed || [];
                var submittedCount = result.submitted_count || 0;
                var skippedCount = result.skipped_count || 0;

                if (failedList.length > 0) {
                    // Show persistent error report for submission failures
                    var failedJobs = failedList.map(function(f) {
                        return { profile_name: f.profile, message: f.reason, phase: 'error' };
                    });
                    var succeededJobs = [];
                    for (var i = 0; i < submittedCount; i++) succeededJobs.push({ phase: 'complete' });
                    showBatchErrorReport(succeededJobs, failedJobs, []);
                }

                if (submittedCount > 0) {
                    window._batchRunning = true;
                    sessionStorage.removeItem('sentinel-batch-seen-errors');
                    showProgress('Batch: ' + result.submitted_count + ' profiles queued — starting...', 5);
                    checkJobCapacity();
                    pollBatchProgress(result.submitted_count);
                } else {
                    hideProgress();
                }
            }
        })
        .catch(function(err) {
            showToast('Failed to submit batch scan: ' + (err.message || err), 'error');
            hideProgress();
        })
        .finally(function() {
            if (spinner) spinner.style.display = 'none';
            // Keep button disabled while batch is running — re-enabled when batch completes
            if (!window._batchRunning && btn) btn.disabled = false;
        });
}

var _batchPollRetryCount = 0;
function pollBatchProgress(totalProfiles) {
    fetch('/analyse/jobs')
        .then(function(r) { _batchPollRetryCount = 0; return r.json(); })
        .then(function(data) {
            var jobs = data.jobs || [];
            var completed = jobs.filter(function(j) {
                return j.phase === 'complete' || j.phase === 'error' || j.phase === 'cancelled';
            }).length;
            var active = jobs.filter(function(j) {
                return j.phase !== 'complete' && j.phase !== 'error' && j.phase !== 'cancelled';
            });

            if (active.length === 0 && completed > 0) {
                window._batchRunning = false;
                hideProgress();
                var scanAllBtn = document.getElementById('scanAllBtn');
                if (scanAllBtn) scanAllBtn.disabled = false;

                var succeeded = jobs.filter(function(j) { return j.phase === 'complete'; });
                var failed = jobs.filter(function(j) { return j.phase === 'error'; });
                var cancelled = jobs.filter(function(j) { return j.phase === 'cancelled'; });

                if (failed.length > 0 && succeeded.length === 0) {
                    // All jobs failed — likely a credential/SSO issue
                    var firstError = failed[0];
                    var msg = firstError.message || 'Unknown error';
                    var firstProfile = firstError.profile_name || firstError.account_id || null;
                    var isSso = msg.toLowerCase().indexOf('sso') >= 0 || msg.toLowerCase().indexOf('expired') >= 0;
                    if (isSso) {
                        showCredentialError('Batch scan failed: all ' + failed.length + ' profiles returned credential errors. ' + msg, firstProfile);
                    } else {
                        showBatchErrorReport(succeeded, failed, cancelled);
                    }
                } else if (failed.length > 0) {
                    // Mixed results — show a report
                    showBatchErrorReport(succeeded, failed, cancelled);
                } else {
                    showToast('Batch scan complete: ' + succeeded.length + ' profiles scanned successfully', 'success');
                }

                refreshDashAccountSelector();
                return;
            }

            // Show toast for newly failed jobs (real-time error notification)
            var errorJobs = jobs.filter(function(j) { return j.phase === 'error'; });
            var seenErrors = {};
            try { seenErrors = JSON.parse(sessionStorage.getItem('sentinel-batch-seen-errors') || '{}'); } catch(e) {}
            var newErrors = false;
            errorJobs.forEach(function(j) {
                var key = j.job_id || j.profile_name;
                if (!seenErrors[key]) {
                    seenErrors[key] = true;
                    newErrors = true;
                    var profile = j.profile_name || j.account_id || 'Unknown';
                    var msg = j.message || 'Scan failed';
                    showToast(profile + ': ' + msg, 'error');
                }
            });
            if (newErrors) sessionStorage.setItem('sentinel-batch-seen-errors', JSON.stringify(seenErrors));

            // Aggregate progress across all non-cancelled jobs
            var relevantJobs = jobs.filter(function(j) { return j.phase !== 'cancelled'; });
            var count = relevantJobs.length || 1;
            var totalProgress = 0;
            relevantJobs.forEach(function(j) {
                var p = j.progress || 0;
                if (j.phase === 'complete' || j.phase === 'error') p = 100;
                totalProgress += p;
            });
            var overallPercent = Math.min(Math.round(totalProgress / count), 100);

            var scanning = active.filter(function(j) { return j.phase === 'scanning' || j.phase === 'ai' || j.phase === 'saving'; }).length;
            var queued = active.filter(function(j) { return j.phase === 'queued'; }).length;
            var failedCount = errorJobs.length;
            var parts = [];
            if (completed > 0) parts.push(completed + ' done');
            if (failedCount > 0) parts.push(failedCount + ' failed');
            if (scanning > 0) parts.push(scanning + ' scanning');
            if (queued > 0) parts.push(queued + ' queued');

            showProgress('Batch: ' + count + ' profiles — ' + parts.join(', ') + ' (' + overallPercent + '%)', overallPercent);
            checkJobCapacity();
            // Refresh dropdown when new reports arrive
            if (completed > (window._lastBatchCompleted || 0)) {
                window._lastBatchCompleted = completed;
                refreshDashAccountSelector();
            }
            setTimeout(function() { pollBatchProgress(totalProfiles); }, 2000);
        })
        .catch(function() {
            if (++_batchPollRetryCount > 20) {
                window._batchRunning = false;
                hideProgress();
                showToast('Lost connection to server during batch scan', 'error');
                return;
            }
            setTimeout(function() { pollBatchProgress(totalProfiles); }, 3000);
        });
}

function stopJob(jobId) {
    fetch('/analyse/cancel?jobId=' + encodeURIComponent(jobId), { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            showToast('Scan stopped', 'info');
            checkJobCapacity();
        })
        .catch(function() { showToast('Failed to stop scan'); });
}

function clearAllCache() {
    if (!confirm('Clear all cached reports? This cannot be undone.')) return;
    fetch('/analyse/cache/clear', { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            sessionStorage.removeItem('sentinel-report');
            sessionStorage.removeItem('sentinel-results');
            showToast(data.message, 'info');
        })
        .catch(function() { showToast('Failed to clear cache'); });
}

function refreshPricing() {
    var btn = document.getElementById('refreshPricingBtn');
    var spinner = document.getElementById('refreshPricingSpinner');
    var resultDiv = document.getElementById('pricingRefreshResult');
    var progressBar = document.getElementById('pricingProgressBar');

    if (btn) btn.disabled = true;
    if (spinner) spinner.style.display = '';
    if (progressBar) progressBar.style.display = '';
    if (resultDiv) resultDiv.style.display = 'none';
    updatePricingProgress('Connecting...', 0);
    showProgress('Refreshing AWS pricing...', 0);
    sessionStorage.setItem('sentinel-pricing-refresh', 'true');

    pollPricingProgress();

    fetch('/pricing/refresh', { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            sessionStorage.removeItem('sentinel-pricing-refresh');
            if (data.status === 'success') {
                updatePricingProgress('Complete', 100);
                showProgress('Pricing updated', 100);
                setTimeout(hideProgress, 3000);
                if (resultDiv) { resultDiv.style.display = ''; resultDiv.innerHTML = '<div class="alert alert-success py-1 mb-0 small">' + esc(data.message) + '</div>'; }
                var lastEl = document.getElementById('pricingLastVerified');
                if (lastEl && data.lastVerified) lastEl.textContent = data.lastVerified;
                showToast('Pricing updated', 'info');
            } else {
                updatePricingProgress('Failed', 0);
                showProgress('Pricing refresh failed', 0);
                setTimeout(hideProgress, 3000);
                if (resultDiv) { resultDiv.style.display = ''; resultDiv.innerHTML = '<div class="alert alert-danger py-1 mb-0 small">' + esc(data.message) + '</div>'; }
            }
        })
        .catch(function() {
            sessionStorage.removeItem('sentinel-pricing-refresh');
            updatePricingProgress('Failed', 0);
            showProgress('Pricing refresh failed', 0);
            setTimeout(hideProgress, 3000);
            if (resultDiv) { resultDiv.style.display = ''; resultDiv.innerHTML = '<div class="alert alert-danger py-1 mb-0 small">Failed to refresh pricing</div>'; }
        })
        .finally(function() {
            if (btn) btn.disabled = false;
            if (spinner) spinner.style.display = 'none';
            setTimeout(function() { if (progressBar) progressBar.style.display = 'none'; }, 3000);
        });
}

function pollPricingProgress() {
    fetch('/pricing/progress')
        .then(function(r) { return r.json(); })
        .then(function(p) {
            updatePricingProgress(p.message, p.percent);
            showProgress('Pricing: ' + (p.message || 'Refreshing...'), p.percent);
            if (p.refreshing || p.percent < 100) {
                setTimeout(pollPricingProgress, 800);
            } else {
                sessionStorage.removeItem('sentinel-pricing-refresh');
            }
        })
        .catch(function() {
            if (sessionStorage.getItem('sentinel-pricing-refresh')) {
                setTimeout(pollPricingProgress, 2000);
            }
        });
}

// Resume pricing progress polling on page load if refresh was in progress
(function() {
    if (sessionStorage.getItem('sentinel-pricing-refresh')) {
        pollPricingProgress();
    }
})();

function updatePricingProgress(message, percent) {
    var msg = document.getElementById('pricingProgressMsg');
    var pct = document.getElementById('pricingProgressPct');
    var fill = document.getElementById('pricingProgressFill');
    if (msg) msg.textContent = message || '';
    if (pct) pct.textContent = percent + '%';
    if (fill) fill.style.width = percent + '%';
}

function loadPricingStatus() {
    var el = document.getElementById('pricingLastVerified');
    if (!el) return;
    fetch('/pricing/status')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            el.textContent = data.lastVerified || 'unknown';
            if (data.refreshing) resumePricingProgress();
        })
        .catch(function() { el.textContent = 'unknown'; });
}

function resumePricingProgress() {
    var btn = document.getElementById('refreshPricingBtn');
    var spinner = document.getElementById('refreshPricingSpinner');
    var progressBar = document.getElementById('pricingProgressBar');
    if (btn) btn.disabled = true;
    if (spinner) spinner.style.display = '';
    if (progressBar) progressBar.style.display = '';

    var pollTimer = setInterval(function() {
        fetch('/pricing/progress')
            .then(function(r) { return r.json(); })
            .then(function(p) {
                updatePricingProgress(p.message, p.percent);
                if (!p.refreshing) {
                    clearInterval(pollTimer);
                    if (btn) btn.disabled = false;
                    if (spinner) spinner.style.display = 'none';
                    if (p.percent >= 100) {
                        var lastEl = document.getElementById('pricingLastVerified');
                        if (lastEl) lastEl.textContent = new Date().toISOString().split('T')[0];
                        showToast('Pricing updated', 'info');
                    }
                    setTimeout(function() { if (progressBar) progressBar.style.display = 'none'; }, 3000);
                }
            })
            .catch(function() { clearInterval(pollTimer); });
    }, 800);
}

function refreshDashAccountSelector() {
    var selector = document.getElementById('dashAccountSelector');
    if (!selector) return;

    function resolveId(p) {
        var parts = p.split('-');
        if (parts.length >= 2) { var last = parts[parts.length - 1]; if (/^\d{12}$/.test(last)) return last; }
        return p;
    }

    Promise.all([
        fetch('/profiles').then(function(r) { return r.json(); }),
        fetch('/analyse/cache/count').then(function(r) { return r.json(); }).catch(function() { return { by_account: {} }; })
    ]).then(function(results) {
        var profiles = results[0].profiles || [];
        var cacheCounts = results[1].by_account || {};
        var profileAccountIds = {};
        profiles.forEach(function(p) { profileAccountIds[resolveId(p)] = true; });

        var currentValue = selector.value;
        selector.innerHTML = '<option value="">Select account...</option>';
        var hasAny = false;
        profiles.forEach(function(p) {
            var accountId = resolveId(p);
            var count = cacheCounts[accountId] || 0;
            if (count > 0) {
                var opt = document.createElement('option'); opt.value = p;
                opt.textContent = p + ' (' + count + ' report' + (count > 1 ? 's' : '') + ')';
                selector.appendChild(opt); hasAny = true;
            }
        });
        Object.keys(cacheCounts).forEach(function(acct) {
            if (!profileAccountIds[acct] && acct !== 'default') {
                var opt = document.createElement('option'); opt.value = acct;
                opt.textContent = acct + ' (' + cacheCounts[acct] + ' report' + (cacheCounts[acct] > 1 ? 's' : '') + ')';
                selector.appendChild(opt); hasAny = true;
            }
        });
        // Show/hide hint
        var hint = document.getElementById('noAccountsHint');
        if (hint) hint.style.display = hasAny ? 'none' : '';
        // Restore selection or auto-select first
        if (currentValue) {
            selector.value = currentValue;
        } else if (hasAny && selector.options.length > 1) {
            selector.selectedIndex = 1;
            var noMsg = document.getElementById('noAnalysisMessage');
            if (noMsg) noMsg.style.display = 'none';
        }
    });
}

document.addEventListener('DOMContentLoaded', function() {
    loadResultsFromSession();
    resumeJobIfRunning();
    updateSidebarJobBadge();
    loadPricingStatus();
    checkAiHealth();
    setInterval(updateSidebarJobBadge, 5000);
});

function checkAiHealth() {
    fetch('/ai/health')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.ollama_status === 'warming') {
                showAiAlert('Ollama model is loading — first scan may take a moment...', 'info');
                // Poll until ready
                setTimeout(checkAiHealth, 5000);
            } else if (data.ollama_status === 'error') {
                showAiAlert('Ollama model failed to load: ' + (data.ollama_error || 'Unknown error') + '. Check Docker container is running.', 'danger');
            } else if (data.ollama_status === 'unavailable') {
                // Don't alert — Ollama simply not configured
            } else if (data.ollama_status === 'ready') {
                hideAiAlert();
            }
        })
        .catch(function() {});
}

function showAiAlert(message, type) {
    var existing = document.getElementById('aiHealthAlert');
    if (existing) existing.remove();
    var alertClass = type === 'danger' ? 'alert-danger' : 'alert-info';
    var alert = document.createElement('div');
    alert.id = 'aiHealthAlert';
    alert.className = 'alert ' + alertClass + ' alert-dismissible mb-0 py-2 small rounded-0';
    alert.style.cssText = 'position:sticky;top:0;z-index:1050;';
    alert.innerHTML = '<strong>AI:</strong> ' + esc(message) +
        '<button type="button" class="btn-close btn-close-sm" onclick="this.parentElement.remove()"></button>';
    var header = document.querySelector('.navbar');
    if (header && header.nextSibling) {
        header.parentNode.insertBefore(alert, header.nextSibling);
    } else {
        document.body.prepend(alert);
    }
}

function hideAiAlert() {
    var existing = document.getElementById('aiHealthAlert');
    if (existing) existing.remove();
}
