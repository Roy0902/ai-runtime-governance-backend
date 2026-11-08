package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import local.governance.config.GovernanceProperties;
import local.governance.config.GovernanceProperties.ModelPrice;
import org.junit.jupiter.api.Test;

class GatewayPricingTest {

    private final GovernanceProperties properties = new GovernanceProperties();
    private final GatewayPricing pricing = new GatewayPricing(properties);

    @Test
    void calculatesInputOutputAndCachedCosts() {
        ModelPrice price = new ModelPrice();
        price.setInputPerMillion(new BigDecimal("2.50"));
        price.setOutputPerMillion(new BigDecimal("10.00"));
        price.setCachedInputPerMillion(new BigDecimal("1.25"));
        properties.getPricing().getModels().put("gpt-4o", price);

        GatewayPricing.Cost cost = pricing.calculate("gpt-4o", 1_000_000L, 1_000_000L, 400_000L, null);

        assertEquals(new BigDecimal("1.50000000"), cost.inputUsd());
        assertEquals(new BigDecimal("0.50000000"), cost.cachedInputUsd());
        assertEquals(new BigDecimal("10.00000000"), cost.outputUsd());
        assertEquals(new BigDecimal("12.00000000"), cost.totalUsd());
        assertEquals("USD", cost.currency());
    }

    @Test
    void splitsReasoningOutputWhenConfigured() {
        ModelPrice price = new ModelPrice();
        price.setOutputPerMillion(new BigDecimal("10.00"));
        price.setReasoningOutputPerMillion(new BigDecimal("20.00"));
        properties.getPricing().getModels().put("reasoning-model", price);

        GatewayPricing.Cost cost = pricing.calculate("reasoning-model", 0L, 1_000_000L, null, 250_000L);

        assertEquals(new BigDecimal("12.50000000"), cost.outputUsd());
    }

    @Test
    void supportsOnlyUsdPricing() {
        ModelPrice price = new ModelPrice();

        assertEquals("USD", price.getCurrency());
        assertThrows(IllegalArgumentException.class, () -> price.setCurrency("EUR"));
        price.setCurrency("usd");
        assertEquals("USD", price.getCurrency());
    }

    @Test
    void leavesCostUnknownForUnpricedModels() {
        GatewayPricing.Cost cost = pricing.calculate("unknown-model", 100L, 50L, null, null);

        assertNull(cost.inputUsd());
        assertNull(cost.outputUsd());
        assertNull(cost.totalUsd());
        assertEquals("USD", cost.currency());
    }
}
