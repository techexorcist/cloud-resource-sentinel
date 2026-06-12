package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;

import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sagemaker.SageMakerClient;
import software.amazon.awssdk.services.sagemaker.model.EndpointSummary;
import software.amazon.awssdk.services.sagemaker.model.NotebookInstanceSummary;

/**
 * Scans SageMaker endpoints and notebook instances for idle or stopped resources.
 *
 * <p>Checks endpoint status and notebook instance status. Notebooks are only priced
 * when InService. Delegates to {@link RecommendationEngine#getSageMakerEndpointRecommendation}
 * and {@link RecommendationEngine#getSageMakerNotebookRecommendation} for classification.</p>
 */
@Component
public class SageMakerScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(SageMakerScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public SageMakerScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans SageMaker endpoints and notebook instances in the given region.
     *
     * <p>Calls {@code listEndpoints} and {@code listNotebookInstances}. Errors on
     * individual endpoints or notebooks are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered SageMaker resources with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var sagemaker = ReadOnlyAwsClientFactory.build(SageMakerClient.builder(), creds, Region.of(region))) {
            for (EndpointSummary endpoint : sagemaker.listEndpoints().endpoints()) {
                try {
                    results.add(this.buildEndpointDto(endpoint, region));
                } catch (Exception e) {
                    log.warn("Failed to process SageMaker endpoint {}: {}", endpoint.endpointName(), e.getMessage());
                }
            }

            for (NotebookInstanceSummary notebook : sagemaker.listNotebookInstances().notebookInstances()) {
                try {
                    results.add(this.buildNotebookDto(notebook, region));
                } catch (Exception e) {
                    log.warn("Failed to process SageMaker notebook {}: {}", notebook.notebookInstanceName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("SageMaker scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single SageMaker endpoint.
     *
     * <p>Populates endpoint status. Cost is $0 (requires describe-endpoint to get instance
     * type, which is not called for performance reasons).</p>
     */
    private ResourceDto buildEndpointDto(EndpointSummary endpoint, String region) {
        String name = endpoint.endpointName();
        String status = endpoint.endpointStatusAsString();
        String description = name + " / " + status;
        String recommendation = engine.getSageMakerEndpointRecommendation(status);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("SageMaker");
        dto.setResourceId(endpoint.endpointArn());
        dto.setResourceName(name);
        dto.setInstanceType(description);
        dto.setState(status);
        dto.setMonthlyCostUsd(0.0); // Endpoint cost requires describe-endpoint to get instance type
        dto.setRecommendation(recommendation);
        if (endpoint.creationTime() != null) {
            dto.setCreatedDate(endpoint.creationTime().toString());
        }

        return dto;
    }

    /**
     * Builds a ResourceDto for a single SageMaker notebook instance.
     *
     * <p>Populates instance type and status. Only InService notebooks incur cost;
     * stopped notebooks report $0.</p>
     */
    private ResourceDto buildNotebookDto(NotebookInstanceSummary notebook, String region) {
        String name = notebook.notebookInstanceName();
        String status = notebook.notebookInstanceStatusAsString();
        String instanceType = notebook.instanceTypeAsString();
        String description = instanceType + " / " + status;
        String recommendation = engine.getSageMakerNotebookRecommendation(status);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("SageMaker");
        dto.setResourceId(notebook.notebookInstanceArn());
        dto.setResourceName(name);
        dto.setInstanceType(description);
        dto.setState(status);
        double cost = "InService".equals(status) ? this.pricingService.getSageMakerNotebookPrice(instanceType, region) : 0.0;
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (notebook.creationTime() != null) {
            dto.setCreatedDate(notebook.creationTime().toString());
        }

        return dto;
    }
}
