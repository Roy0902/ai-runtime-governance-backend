package local.governance.gateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import local.governance.config.GovernanceProperties;
import local.governance.config.GovernanceProperties.ModelPrice;
import org.springframework.stereotype.Service;

/**
 * Converts token counts into cost when the deployment has configured a price
 * for the model. Unknown models keep their token counts but have no cost; the
 * gateway never guesses a price.
 */
@Service
public class GatewayPricing {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final int SCALE = 8;

    public record Cost(
            BigDecimal inputUsd,
            BigDecimal outputUsd,
            BigDecimal cachedInputUsd,
            BigDecimal totalUsd,
            String currency) {}

    private final GovernanceProperties properties;

    public GatewayPricing(GovernanceProperties properties) {
        this.properties = properties;
    }

    public Cost calculate(
            String model,
            Long inputTokens,
            Long outputTokens,
            Long cachedInputTokens,
            Long reasoningOutputTokens) {
        if (model == null) {
            return empty();
        }
        Map<String, ModelPrice> prices = properties.getPricing().getModels();
        ModelPrice price = prices.get(model);
        if (price == null) {
            return empty();
        }
        String currency = price.getCurrency();

        Long input = positive(inputTokens);
        Long output = positive(outputTokens);
        Long cached = positive(cachedInputTokens);
        Long reasoning = positive(reasoningOutputTokens);

        BigDecimal inputCost = null;
        BigDecimal cachedCost = null;
        if (input != null && price.getInputPerMillion() != null) {
            long uncached = input;
            if (cached != null && cached <= input && price.getCachedInputPerMillion() != null) {
                uncached = input - cached;
                cachedCost = cost(cached, price.getCachedInputPerMillion());
            }
            inputCost = cost(uncached, price.getInputPerMillion());
        }

        BigDecimal outputCost = null;
        if (output != null && price.getOutputPerMillion() != null) {
            if (reasoning != null && reasoning <= output && price.getReasoningOutputPerMillion() != null) {
                long standard = output - reasoning;
                BigDecimal reasoningCost = cost(reasoning, price.getReasoningOutputPerMillion());
                outputCost = cost(standard, price.getOutputPerMillion()).add(reasoningCost);
            } else {
                outputCost = cost(output, price.getOutputPerMillion());
            }
        }

        BigDecimal total = add(inputCost, outputCost, cachedCost);
        return new Cost(inputCost, outputCost, cachedCost, total, currency);
    }

    private static BigDecimal cost(long tokens, BigDecimal perMillion) {
        return BigDecimal.valueOf(tokens)
                .multiply(perMillion)
                .divide(MILLION, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal add(BigDecimal... values) {
        BigDecimal total = null;
        for (BigDecimal value : values) {
            if (value == null) {
                continue;
            }
            total = total == null ? value : total.add(value);
        }
        return total;
    }

    private static Long positive(Long value) {
        return value == null || value < 0 ? null : value;
    }

    private static Cost empty() {
        return new Cost(null, null, null, null, "USD");
    }
}
