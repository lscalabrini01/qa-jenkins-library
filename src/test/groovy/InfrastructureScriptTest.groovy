import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName

import static org.assertj.core.api.Assertions.assertThat

/**
 * Tests for vars/infrastructure.groovy.
 *
 * generateWorkspaceName() and parseAndSubstituteVars() are data-only
 * operations that don't call Jenkins steps, so they are tested directly
 * without a steps mock involved in assertions.
 *
 * Note: generateWorkspaceName uses Date.format() which is problematic
 * in Groovy 3.x. Tests here use includeTimestamp: false to avoid that
 * code path, focusing on the prefix/suffix logic.
 *
 * detectPublicIp() calls steps.sh/steps.echo and error(), so a stepsMock
 * (capturing echo/sh calls) is set up in setUp() and reused across tests.
 */
class InfrastructureScriptTest extends BasePipelineTest {

    def script
    def stepsMock
    def echoLog = []
    def capturedShCommand = null

    @Override
    @BeforeEach
    void setUp() {
        super.setUp()

        // Make env available to `new config()` inside infrastructure.groovy
        def configScript = loadScript('config.groovy')
        configScript.class.metaClass.env = binding.getVariable('env')

        echoLog = []
        capturedShCommand = null

        stepsMock = new Object()
        stepsMock.metaClass.echo = { String msg -> echoLog.add(msg) }
        stepsMock.metaClass.sh = { Map m ->
            capturedShCommand = m['script'] as String
            return '203.0.113.42'
        }

        binding.setVariable('steps', stepsMock)

        script = loadScript('infrastructure.groovy')
        script.class.metaClass.steps = stepsMock
    }

    // ── generateWorkspaceName ─────────────────────────────────────────

    @Test
    @DisplayName('generateWorkspaceName uses default prefix and BUILD_NUMBER')
    void generateWorkspaceName_defaults() {
        // BUILD_NUMBER is set to '42' by BasePipelineTest
        // Use includeTimestamp: false to avoid Date.format() Groovy 3 issue
        def name = script.generateWorkspaceName(includeTimestamp: false)

        assertThat((String) name).isEqualTo('jenkins_workspace_42')
    }

    @Test
    @DisplayName('generateWorkspaceName uses custom prefix')
    void generateWorkspaceName_customPrefix() {
        def name = script.generateWorkspaceName(
            prefix: 'rancher_airgap',
            includeTimestamp: false
        )

        assertThat((String) name).isEqualTo('rancher_airgap_42')
    }

    @Test
    @DisplayName('generateWorkspaceName appends sanitized suffix')
    void generateWorkspaceName_withSuffix() {
        def name = script.generateWorkspaceName(
            prefix: 'test',
            suffix: 'my-cluster',
            includeTimestamp: false
        )

        assertThat((String) name).isEqualTo('test_42_my-cluster')
    }

    @Test
    @DisplayName('generateWorkspaceName sanitizes special characters in suffix')
    void generateWorkspaceName_sanitizesSuffix() {
        def name = script.generateWorkspaceName(
            prefix: 'test',
            suffix: 'my cluster @#$% name!',
            includeTimestamp: false
        )

        assertThat((String) name).isEqualTo('test_42_my-cluster-name')
    }

    @Test
    @DisplayName('generateWorkspaceName collapses multiple hyphens in suffix')
    void generateWorkspaceName_collapsesHyphens() {
        def name = script.generateWorkspaceName(
            prefix: 'test',
            suffix: 'a---b',
            includeTimestamp: false
        )

        assertThat((String) name).isEqualTo('test_42_a-b')
    }

    @Test
    @DisplayName('generateWorkspaceName strips leading/trailing hyphens from suffix')
    void generateWorkspaceName_stripsEdgeHyphens() {
        def name = script.generateWorkspaceName(
            prefix: 'test',
            suffix: '--hello--',
            includeTimestamp: false
        )

        assertThat((String) name).isEqualTo('test_42_hello')
    }

    @Test
    @DisplayName('generateWorkspaceName without timestamp when includeTimestamp is false')
    void generateWorkspaceName_noTimestamp() {
        def name = script.generateWorkspaceName(
            prefix: 'test',
            includeTimestamp: false
        )

        assertThat((String) name).isEqualTo('test_42')
    }

    @Test
    @DisplayName('generateWorkspaceName with suffix and no timestamp')
    void generateWorkspaceName_suffixNoTimestamp() {
        def name = script.generateWorkspaceName(
            prefix: 'ws',
            suffix: 'rke2',
            includeTimestamp: false
        )

        assertThat((String) name).isEqualTo('ws_42_rke2')
    }

    @Test
    @DisplayName('generateWorkspaceName omits suffix section when suffix is empty after sanitization')
    void generateWorkspaceName_emptySuffixOmitted() {
        def name = script.generateWorkspaceName(
            prefix: 'ws',
            suffix: '@@@',
            includeTimestamp: false
        )

        // '@@@' becomes '---' then collapsed to '-' then stripped to ''
        assertThat((String) name).isEqualTo('ws_42')
    }

    @Test
    @DisplayName('generateWorkspaceName uses unknown when BUILD_NUMBER missing')
    void generateWorkspaceName_missingBuildNumber() {
        // Clear BUILD_NUMBER from the env mock map
        def envMap = binding.getVariable('env') as Map
        envMap.remove('BUILD_NUMBER')

        def name = script.generateWorkspaceName(
            prefix: 'test',
            includeTimestamp: false
        )

        assertThat((String) name).isEqualTo('test_unknown')
    }

    // ── parseAndSubstituteVars ────────────────────────────────────────

    @Test
    @DisplayName('parseAndSubstituteVars replaces ${VAR} syntax')
    void parseAndSubstituteVars_dollarBraceSyntax() {
        def result = script.parseAndSubstituteVars(
            content: 'host: ${HOST}\nport: ${PORT}',
            envVars: [HOST: 'rancher.local', PORT: '8080']
        )

        assertThat((String) result).isEqualTo('host: rancher.local\nport: 8080')
    }

    @Test
    @DisplayName('parseAndSubstituteVars replaces $VAR syntax')
    void parseAndSubstituteVars_dollarSyntax() {
        def result = script.parseAndSubstituteVars(
            content: 'region=$REGION',
            envVars: [REGION: 'us-east-1']
        )

        assertThat((String) result).isEqualTo('region=us-east-1')
    }

    @Test
    @DisplayName('parseAndSubstituteVars does not partially match longer variable names')
    void parseAndSubstituteVars_noPartialMatch() {
        def result = script.parseAndSubstituteVars(
            content: '$REGIONAL',
            envVars: [REGION: 'us-east-1']
        )

        // $REGION should NOT match $REGIONAL because REGIONAL has more identifier chars
        assertThat((String) result).isEqualTo('$REGIONAL')
    }

    @Test
    @DisplayName('parseAndSubstituteVars replaces multiple occurrences')
    void parseAndSubstituteVars_multipleOccurrences() {
        def result = script.parseAndSubstituteVars(
            content: '${HOST} and ${HOST} again',
            envVars: [HOST: 'server']
        )

        assertThat((String) result).isEqualTo('server and server again')
    }

    @Test
    @DisplayName('parseAndSubstituteVars leaves unmatched vars unchanged')
    void parseAndSubstituteVars_unmatchedVars() {
        def result = script.parseAndSubstituteVars(
            content: '${KNOWN} and ${UNKNOWN}',
            envVars: [KNOWN: 'value']
        )

        assertThat((String) result).isEqualTo('value and ${UNKNOWN}')
    }

    @Test
    @DisplayName('parseAndSubstituteVars returns content unchanged when no envVars provided')
    void parseAndSubstituteVars_noEnvVars() {
        def result = script.parseAndSubstituteVars(
            content: 'plain text ${NOPE}'
        )

        assertThat((String) result).isEqualTo('plain text ${NOPE}')
    }

    @Test
    @DisplayName('parseAndSubstituteVars throws when content is missing')
    void parseAndSubstituteVars_throwsForMissingContent() {
        helper.registerAllowedMethod('error', [String.class]) { String msg ->
            throw new RuntimeException(msg)
        }

        RuntimeException ex = null
        try {
            script.parseAndSubstituteVars([:])
        } catch (RuntimeException e) {
            ex = e
        }

        assertThat(ex).isNotNull()
        assertThat((String) ex.message).contains('Content must be provided')
    }

    @Test
    @DisplayName('parseAndSubstituteVars handles both $VAR and ${VAR} in same content')
    void parseAndSubstituteVars_mixedSyntax() {
        def result = script.parseAndSubstituteVars(
            content: 'host=${HOST} and region=$REGION',
            envVars: [HOST: 'server', REGION: 'us-east-1']
        )

        assertThat((String) result).isEqualTo('host=server and region=us-east-1')
    }

    // ── detectPublicIp ────────────────────────────────────────────────

    @Test
    @DisplayName('detectPublicIp returns a valid, trimmed IPv4 address')
    void detectPublicIp_returnsValidIp() {
        stepsMock.metaClass.sh = { Map m -> '  203.0.113.42  ' }

        def ip = script.detectPublicIp()

        assertThat((String) ip).isEqualTo('203.0.113.42')
    }

    @Test
    @DisplayName('detectPublicIp echoes the detected IP')
    void detectPublicIp_echoesDetectedIp() {
        script.detectPublicIp()

        assertThat(echoLog).contains('Detected runner public IP: 203.0.113.42')
    }

    @Test
    @DisplayName('detectPublicIp defaults timeout to 5 seconds')
    void detectPublicIp_defaultsTimeout() {
        script.detectPublicIp()

        assertThat((String) capturedShCommand).contains('--max-time 5')
    }

    @Test
    @DisplayName('detectPublicIp honours a custom timeout')
    void detectPublicIp_customTimeout() {
        script.detectPublicIp(timeout: 10)

        assertThat((String) capturedShCommand).contains('--max-time 10')
    }

    @Test
    @DisplayName('detectPublicIp builds a fallback chain from the default IP services')
    void detectPublicIp_usesDefaultServices() {
        script.detectPublicIp()

        assertThat((String) capturedShCommand)
            .contains('https://ifconfig.me')
            .contains('https://api.ipify.org')
            .contains('https://ipinfo.io/ip')
    }

    @Test
    @DisplayName('detectPublicIp uses services overridden via PUBLIC_IP_SERVICES env var')
    void detectPublicIp_usesConfiguredServices() {
        def envMap = binding.getVariable('env') as Map
        envMap['PUBLIC_IP_SERVICES'] = 'https://one.example.com, https://two.example.com'

        script.detectPublicIp()

        assertThat((String) capturedShCommand)
            .contains('https://one.example.com')
            .contains('https://two.example.com')
            .doesNotContain('https://ifconfig.me')
    }

    @Test
    @DisplayName('detectPublicIp throws when output is not a valid IPv4 address')
    void detectPublicIp_throwsForInvalidIp() {
        helper.registerAllowedMethod('error', [String.class]) { String msg ->
            throw new RuntimeException(msg)
        }
        stepsMock.metaClass.sh = { Map m -> 'not-an-ip' }

        RuntimeException ex = null
        try {
            script.detectPublicIp()
        } catch (RuntimeException e) {
            ex = e
        }

        assertThat(ex).isNotNull()
        assertThat((String) ex.message).contains("Failed to auto-detect a valid public IPv4 address (got: 'not-an-ip')")
    }

    @Test
    @DisplayName('detectPublicIp throws when an octet exceeds 255')
    void detectPublicIp_throwsForOutOfRangeOctet() {
        helper.registerAllowedMethod('error', [String.class]) { String msg ->
            throw new RuntimeException(msg)
        }
        stepsMock.metaClass.sh = { Map m -> '256.1.1.1' }

        RuntimeException ex = null
        try {
            script.detectPublicIp()
        } catch (RuntimeException e) {
            ex = e
        }

        assertThat(ex).isNotNull()
        assertThat((String) ex.message).contains('Failed to auto-detect a valid public IPv4 address')
    }

    @Test
    @DisplayName('detectPublicIp throws when there are not exactly four octets')
    void detectPublicIp_throwsForWrongOctetCount() {
        helper.registerAllowedMethod('error', [String.class]) { String msg ->
            throw new RuntimeException(msg)
        }
        stepsMock.metaClass.sh = { Map m -> '1.2.3' }

        RuntimeException ex = null
        try {
            script.detectPublicIp()
        } catch (RuntimeException e) {
            ex = e
        }

        assertThat(ex).isNotNull()
        assertThat((String) ex.message).contains('Failed to auto-detect a valid public IPv4 address')
    }
}
