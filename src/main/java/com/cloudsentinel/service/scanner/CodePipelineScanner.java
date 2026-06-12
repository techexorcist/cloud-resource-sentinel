package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.codepipeline.model.GetPipelineStateResponse;
import software.amazon.awssdk.services.codepipeline.model.PipelineSummary;
import software.amazon.awssdk.services.codepipeline.model.StageState;

/**
 * Scans AWS CodePipeline pipelines for idle or abandoned pipelines.
 *
 * <p>Checks days since last pipeline activity based on the updated/created timestamp.
 * Delegates to {@link RecommendationEngine#getCodePipelineRecommendation} for classification.</p>
 */
@Component
public class CodePipelineScanner implements ResourceScanner {

    private static final Logger log = LoggerFactory.getLogger(CodePipelineScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public CodePipelineScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    @Override
    public ScanCategory category() {
        return ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Scans CodePipeline pipelines in the given region.
     *
     * <p>Calls {@code listPipelines} and {@code getPipelineState} per pipeline.
     * Errors on individual pipelines are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered pipelines with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (CodePipelineClient pipeline = ReadOnlyAwsClientFactory.build(CodePipelineClient.builder(), creds, Region.of(region))) {
            pipeline.listPipelines().pipelines().forEach(summary -> {
                try {
                    results.add(buildDto(summary, pipeline, region));
                } catch (Exception e) {
                    log.warn("Failed to process CodePipeline {}: {}", summary.name(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("CodePipeline scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single CodePipeline.
     *
     * <p>Fetches pipeline state to determine stage count and last execution status.
     * Computes days since last activity from the updated/created timestamp.</p>
     */
    private ResourceDto buildDto(PipelineSummary summary, CodePipelineClient pipeline, String region) {
        String pipelineName = summary.name();

        GetPipelineStateResponse stateResponse = pipeline.getPipelineState(r -> r.name(pipelineName));
        List<StageState> stages = stateResponse.stageStates();
        int stageCount = stages != null ? stages.size() : 0;
        String instanceType = stageCount + " stages";

        // Determine state and last execution time
        String state = "unknown";
        Instant lastExecutionTime = null;

        if (stages != null && !stages.isEmpty()) {
            for (StageState stage : stages) {
                if (stage.latestExecution() != null) {
                    state = stage.latestExecution().statusAsString();
                    // Use the pipeline-level updated timestamp from summary
                    break;
                }
            }
        }

        // Use the pipeline's updated or created time to estimate last activity
        Instant lastActivity = summary.updated() != null ? summary.updated() : summary.created();

        long daysSinceLastActivity = lastActivity != null
                ? ChronoUnit.DAYS.between(lastActivity, Instant.now()) : Long.MAX_VALUE;
        String recommendation = engine.getCodePipelineRecommendation(daysSinceLastActivity);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("CodePipeline");
        dto.setResourceId(pipelineName);
        dto.setResourceName(pipelineName);
        dto.setInstanceType(instanceType);
        dto.setState(state);
        double cost = recommendation.contains("Idle") ? 0.0 : 1.0;
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (summary.created() != null) {
            dto.setCreatedDate(summary.created().toString());
        }

        return dto;
    }
}
