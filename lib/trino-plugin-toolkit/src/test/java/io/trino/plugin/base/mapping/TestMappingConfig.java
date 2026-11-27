/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.base.mapping;

import com.google.common.collect.ImmutableMap;
import io.airlift.configuration.ConfigurationFactory;
import io.airlift.units.Duration;
import jakarta.validation.constraints.AssertTrue;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static io.airlift.testing.ValidationAssertions.assertFailsValidation;
import static io.airlift.testing.ValidationAssertions.assertValidates;
import static io.trino.plugin.base.mapping.RuleBasedIdentifierMappingUtils.createRuleBasedIdentifierMappingFile;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class TestMappingConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(MappingConfig.class)
                .setCaseInsensitiveNameMatching(false)
                .setCaseInsensitiveNameMatchingCacheTtl(new Duration(1, MINUTES))
                .setCaseInsensitiveNameMatchingConfigFile(null)
                .setCaseInsensitiveNameMatchingConfigFileRefreshPeriod(null)
                .setCaseSensitiveNameMatching(false));
    }

    @Test
    public void testExplicitPropertyMappings()
            throws Exception
    {
        String configFile = createRuleBasedIdentifierMappingFile().toFile().getAbsolutePath();
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("case-insensitive-name-matching", "true")
                .put("case-insensitive-name-matching.cache-ttl", "1s")
                .put("case-insensitive-name-matching.config-file", configFile)
                .put("case-insensitive-name-matching.config-file.refresh-period", "1s")
                .buildOrThrow();

        MappingConfig expected = new MappingConfig()
                .setCaseInsensitiveNameMatching(true)
                .setCaseInsensitiveNameMatchingCacheTtl(new Duration(1, SECONDS))
                .setCaseInsensitiveNameMatchingConfigFile(configFile)
                .setCaseInsensitiveNameMatchingConfigFileRefreshPeriod(new Duration(1, SECONDS));

        // case-sensitive-name-matching stays at its default here: it cannot be enabled together with the properties above.
        // Airlift 338 assertFullMapping requires every property to be set to a non-default value, so compare the bound config directly.
        MappingConfig actual = new ConfigurationFactory(properties).build(MappingConfig.class);
        assertThat(actual.isCaseInsensitiveNameMatching()).isEqualTo(expected.isCaseInsensitiveNameMatching());
        assertThat(actual.getCaseInsensitiveNameMatchingCacheTtl()).isEqualTo(expected.getCaseInsensitiveNameMatchingCacheTtl());
        assertThat(actual.getCaseInsensitiveNameMatchingConfigFile()).isEqualTo(expected.getCaseInsensitiveNameMatchingConfigFile());
        assertThat(actual.getCaseInsensitiveNameMatchingConfigFileRefreshPeriod()).isEqualTo(expected.getCaseInsensitiveNameMatchingConfigFileRefreshPeriod());
        assertThat(actual.isCaseSensitiveNameMatching()).isFalse();
    }

    @Test
    public void testExplicitCaseSensitiveNameMatchingMapping()
    {
        MappingConfig actual = new ConfigurationFactory(ImmutableMap.of("case-sensitive-name-matching", "true")).build(MappingConfig.class);
        assertThat(actual.isCaseSensitiveNameMatching()).isTrue();
        assertThat(actual.isCaseInsensitiveNameMatching()).isFalse();
        assertThat(actual.getCaseInsensitiveNameMatchingConfigFile()).isEmpty();
    }

    @Test
    public void testCaseSensitiveNameMatchingValidation()
            throws Exception
    {
        assertValidates(new MappingConfig().setCaseSensitiveNameMatching(true));

        String message = "case-sensitive-name-matching cannot be combined with case-insensitive-name-matching or case-insensitive-name-matching.config-file";
        assertFailsValidation(
                new MappingConfig()
                        .setCaseSensitiveNameMatching(true)
                        .setCaseInsensitiveNameMatching(true),
                "caseSensitiveNameMatchingValid",
                message,
                AssertTrue.class);
        assertFailsValidation(
                new MappingConfig()
                        .setCaseSensitiveNameMatching(true)
                        .setCaseInsensitiveNameMatchingConfigFile(createRuleBasedIdentifierMappingFile().toFile().getAbsolutePath()),
                "caseSensitiveNameMatchingValid",
                message,
                AssertTrue.class);
    }
}
