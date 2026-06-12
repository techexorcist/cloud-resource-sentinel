package com.cloudsentinel.service;

import com.cloudsentinel.service.pricing.FallbackPricing;
import com.cloudsentinel.service.pricing.RegionalPricingData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingServiceTest {

    private static final double HOURS = 730.0;
    private static FallbackPricing pricing;

    @BeforeAll
    static void setup() {
        RegionalPricingData data = new RegionalPricingData();
        data.load();
        pricing = new FallbackPricing(data);
    }

    @Test
    void ec2Pricing_usEast1() {
        assertEquals(round(0.0104 * HOURS), pricing.getEc2Price("t3.micro", "us-east-1"));
        assertEquals(round(0.0208 * HOURS), pricing.getEc2Price("t3.small", "us-east-1"));
        assertEquals(round(0.0416 * HOURS), pricing.getEc2Price("t3.medium", "us-east-1"));
        assertEquals(round(0.096 * HOURS), pricing.getEc2Price("m5.large", "us-east-1"));
        assertEquals(round(0.085 * HOURS), pricing.getEc2Price("c5.large", "us-east-1"));
        assertEquals(round(0.126 * HOURS), pricing.getEc2Price("r5.large", "us-east-1"));
    }

    @Test
    void ec2Pricing_regionalVariation() {
        double usEast = pricing.getEc2Price("t3.micro", "us-east-1");
        double saEast = pricing.getEc2Price("t3.micro", "sa-east-1");
        double apTokyo = pricing.getEc2Price("t3.micro", "ap-northeast-1");

        assertTrue(saEast > usEast, "sa-east-1 should be more expensive than us-east-1");
        assertTrue(apTokyo > usEast, "ap-northeast-1 should be more expensive than us-east-1");
    }

    @Test
    void ec2Pricing_unknownUsesEstimate() {
        double price = pricing.getEc2Price("z9.large", "us-east-1");
        assertTrue(price > 0, "Unknown instance type should return estimated price");
    }

    @Test
    void rdsPricing() {
        // Prices may vary between classpath and disk-cached pricing data
        double t3micro = pricing.getRdsPrice("db.t3.micro", "us-east-1");
        double m5large = pricing.getRdsPrice("db.m5.large", "us-east-1");
        double r5large = pricing.getRdsPrice("db.r5.large", "us-east-1");
        assertTrue(t3micro > 10 && t3micro < 20, "db.t3.micro should be ~$12-15/mo, got $" + t3micro);
        assertTrue(m5large > 100 && m5large < 200, "db.m5.large should be ~$125-175/mo, got $" + m5large);
        assertTrue(r5large > 150 && r5large < 250, "db.r5.large should be ~$180-220/mo, got $" + r5large);
    }

    @Test
    void rdsPricing_regionalVariation() {
        double usEast = pricing.getRdsPrice("db.m5.large", "us-east-1");
        double saEast = pricing.getRdsPrice("db.m5.large", "sa-east-1");
        assertTrue(saEast > usEast, "sa-east-1 RDS should be more expensive");
    }

    @Test
    void ebsPricing() {
        assertEquals(round(100 * 0.10), pricing.getEbsPrice(100, "gp2", "us-east-1"));
        assertEquals(round(100 * 0.08), pricing.getEbsPrice(100, "gp3", "us-east-1"));
    }

    @Test
    void ebsPricing_regional() {
        double usEast = pricing.getEbsPrice(100, "gp2", "us-east-1");
        double saEast = pricing.getEbsPrice(100, "gp2", "sa-east-1");
        assertTrue(saEast > usEast, "sa-east-1 EBS should be more expensive");
    }

    @Test
    void servicePricing() {
        assertTrue(pricing.getNatGatewayPrice("us-east-1") > 0);
        assertTrue(pricing.getEksClusterPrice("us-east-1") > 0);
        assertTrue(pricing.getS3PricePerGb("us-east-1") > 0);
        assertTrue(pricing.getElasticIpPrice("us-east-1") > 0);
    }

    @Test
    void servicePricing_regional() {
        double usEast = pricing.getNatGatewayPrice("us-east-1");
        double saEast = pricing.getNatGatewayPrice("sa-east-1");
        assertTrue(saEast > usEast, "sa-east-1 NAT Gateway should be more expensive");
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
