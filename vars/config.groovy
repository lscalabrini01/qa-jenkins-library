/**
 * config.groovy
 *
 * Centralized configuration management for the Jenkins shared library.
 *
 * Provides a single source of truth for default values used across all other
 * modules (container, tofu, ansible, naming, property, etc.). Every setting
 * has an environment variable override so that values can be changed at the
 * Jenkins folder, job, or build level without touching library code.
 *
 * Configuration sections
 * ----------------------
 *   docker   - Image names and target platform
 *   testing  - gotestsum defaults (tags, timeout, result file paths)
 *   naming   - Container/image name conventions
 *   ui       - ANSI colour map for build log colouring
 *   paths    - Common directory locations
 *
 * Usage
 * -----
 *   def cfg = new config()
 *   def dockerCfg = cfg.getConfig('docker')      // full section map
 *   def image     = cfg.getDockerImage()          // convenience accessor
 */

/**
 * Return the full default configuration map.
 *
 * Each leaf value falls back to an environment variable, then a hardcoded
 * default, so the map is always fully populated.
 *
 * Returns a Map with sections: docker, testing, naming, ui, paths.
 */
def getDefaultConfig() {
    return [
        // Docker configuration
        docker: [
            images: [
                infraTools: env.RANCHER_INFRA_TOOLS_IMAGE ?: 'rancher-infra-tools:latest'
            ],
            platform: env.DOCKER_PLATFORM ?: 'linux/amd64',
            defaultEnvFile: env.DOCKER_DEFAULT_ENV_FILE ?: '.env'
        ],

        // Testing configuration
        testing: [
            defaultTags: env.TEST_DEFAULT_TAGS ?: 'validation',
            defaultTimeout: env.TEST_DEFAULT_TIMEOUT ?: '60m',
            defaultResultsXML: env.TEST_DEFAULT_RESULTS_XML ?: 'results.xml',
            defaultResultsJSON: env.TEST_DEFAULT_RESULTS_JSON ?: 'results.json'
        ],

        // Naming conventions
        naming: [
            containerSuffix: env.CONTAINER_SUFFIX ?: 'test',
            imagePrefix: env.IMAGE_PREFIX ?: 'rancher-validation-'
        ],

        // UI/Display configuration
        ui: [
            colorMapName: env.COLOR_MAP_NAME ?: 'XTerm',
            defaultFg: env.DEFAULT_FG_COLOR ? env.DEFAULT_FG_COLOR.toInteger() : 2,
            defaultBg: env.DEFAULT_BG_COLOR ? env.DEFAULT_BG_COLOR.toInteger() : 1
        ],

        // Path configuration
        paths: [
            defaultDir: env.DEFAULT_DIR ?: '.',
            sshDir: env.SSH_DIR ?: '.ssh',
            validationDir: env.VALIDATION_DIR ?: 'validation'
        ],

        // Network configuration
        network: [
            publicIpServices: env.PUBLIC_IP_SERVICES ?
                env.PUBLIC_IP_SERVICES.split(',').collect { it.trim() } :
                ['https://ifconfig.me', 'https://api.ipify.org', 'https://ipinfo.io/ip']
        ],

        // Repository configuration
        repositories: [
            tests: [
                url:    env.RANCHER_TESTS_REPO_URL       ?: 'https://github.com/rancher/tests.git',
                branch: env.RANCHER_TESTS_REPO_BRANCH    ?: 'main',
                target: env.RANCHER_TESTS_REPO_TARGET     ?: 'tests'
            ],
            qaInfraAutomation: [
                url:    env.QA_INFRA_AUTOMATION_REPO_URL    ?: 'https://github.com/rancher/qa-infra-automation.git',
                branch: env.QA_INFRA_AUTOMATION_REPO_BRANCH ?: 'main',
                target: env.QA_INFRA_AUTOMATION_REPO_TARGET  ?: 'qa-infra-automation'
        ]
    ]
    ]
}

/**
 * Return one configuration section by name.
 *
 * Parameters:
 *   section (String, required) - Section key: 'docker', 'testing', 'naming', 'ui', or 'paths'.
 *
 * Returns the section Map, or an empty Map if the section does not exist.
 *
 * Example:
 *   def dockerCfg = new config().getConfig('docker')
 *   def image = dockerCfg.images.infraTools
 */
def getConfig(String section) {
    def cfg = getDefaultConfig()
    return cfg[section] ?: [:]
}

/**
 * Return a single value from a configuration section, with an optional fallback.
 *
 * Parameters:
 *   section      (String, required) - Section key (e.g. 'docker').
 *   key          (String, required) - Key within the section (e.g. 'platform').
 *   defaultValue (any,    optional) - Value returned when the key is absent. Defaults to null.
 *
 * Example:
 *   def platform = new config().getConfigValue('docker', 'platform', 'linux/amd64')
 */
def getConfigValue(String section, String key, def defaultValue = null) {
    def cfg = getDefaultConfig()
    def sectionConfig = cfg[section] ?: [:]
    return sectionConfig[key] ?: defaultValue
}

/**
 * Deep-merge a caller-supplied map on top of the default configuration.
 *
 * Nested maps are merged recursively; scalar values in userConfig overwrite
 * the corresponding defaults.
 *
 * Parameters:
 *   userConfig (Map, optional) - Overrides to apply. Defaults to empty map.
 *
 * Returns the merged configuration Map.
 *
 * Example:
 *   def cfg = new config().mergeConfig([docker: [platform: 'linux/arm64']])
 */
def mergeConfig(Map userConfig = [:]) {
    def defaultConfig = getDefaultConfig()

    // Deep merge configuration
    return deepMerge(defaultConfig, userConfig)
}

/**
 * Recursively merge source into target, returning the merged map.
 *
 * When both maps contain a nested map at the same key the maps are merged
 * rather than replaced. All other value types in source overwrite target.
 *
 * Parameters:
 *   target (Map, required) - Base map to merge into (mutated in place).
 *   source (Map, required) - Overrides to apply.
 *
 * Returns target after merging.
 */
def deepMerge(Map target, Map source) {
    source.each { key, value ->
        if (value instanceof Map && target[key] instanceof Map) {
            target[key] = deepMerge(target[key], value)
        } else {
            target[key] = value
        }
    }
    return target
}

/**
 * Validate that a configuration map contains all values required for
 * the library to function. Calls error() if any required value is missing.
 *
 * Parameters:
 *   config (Map, required) - Configuration map to validate (typically from mergeConfig()).
 *
 * Returns true when validation passes.
 *
 * Example:
 *   def cfg = new config().mergeConfig()
 *   new config().validateConfig(cfg)
 */
def validateConfig(Map config) {
    def errors = []

    // Validate required Docker configuration
    if (!config.docker?.images?.infraTools) {
        errors.add("Docker infra tools image is required")
    }

    if (!config.docker?.platform) {
        errors.add("Docker platform is required")
    }

    // Validate required testing configuration
    if (!config.testing?.defaultTags) {
        errors.add("Default test tags are required")
    }

    if (!config.testing?.defaultTimeout) {
        errors.add("Default test timeout is required")
    }

    if (errors) {
        error "Configuration validation failed: ${errors.join(', ')}"
    }

    return true
}

/**
 * Convenience accessor — return the Docker image name for a given image type.
 *
 * Parameters:
 *   imageType (String, optional) - Key under docker.images. Defaults to 'infraTools'.
 *
 * Returns the image name string.
 *
 * Example:
 *   def image = new config().getDockerImage()
 *   // → 'rancher-infra-tools:latest'  (or the value of RANCHER_INFRA_TOOLS_IMAGE)
 */
def getDockerImage(String imageType = 'infraTools') {
    def dockerConfig = getConfig('docker')
    return dockerConfig.images[imageType]
}

/**
 * Convenience accessor — return the Docker platform string.
 *
 * Returns the value of DOCKER_PLATFORM, or 'linux/amd64' by default.
 */
def getDockerPlatform() {
    return getConfigValue('docker', 'platform')
}

/**
 * Convenience accessor — return the full testing configuration section.
 *
 * Returns a Map with keys: defaultTags, defaultTimeout, defaultResultsXML, defaultResultsJSON.
 */
def getTestConfig() {
    return getConfig('testing')
}

/**
 * Convenience accessor — return the full naming configuration section.
 *
 * Returns a Map with keys: containerSuffix, imagePrefix.
 */
def getNamingConfig() {
    return getConfig('naming')
}

/**
 * Convenience accessor — return the full UI configuration section.
 *
 * Returns a Map with keys: colorMapName, defaultFg, defaultBg.
 */
def getUIConfig() {
    return getConfig('ui')
}

/**
 * Convenience accessor — return the full paths configuration section.
 *
 * Returns a Map with keys: defaultDir, sshDir, validationDir.
 */
def getPathConfig() {
    return getConfig('paths')
}

/**
 * Convenience accessor — return the configuration for a named repository.
 *
 * Looks up a repository by key under the 'repositories' section. Supported
 * keys are 'tests' and 'qaInfraAutomation' by default.
 *
 * Parameters:
 *   name (String, required) - Repository key (e.g. 'tests', 'qaInfraAutomation').
 *
 * Returns a Map with keys: url, branch, target.
 *
 * Example:
 *   def repo = new config().getRepositoryConfig('tests')
 *   // repo.url    → 'https://github.com/rancher/tests.git'
 *   // repo.branch → 'main'
 *   // repo.target → 'tests'
 */
def getRepositoryConfig(String name) {
    def repoConfig = getConfig('repositories')
    return repoConfig[name] ?: [:]
}

/**
 * Convenience accessor — return the list of public IP detection service URLs.
 *
 * Used by infrastructure.detectPublicIp() to try each service in order
 * until one returns a valid IPv4 address. Override with the PUBLIC_IP_SERVICES
 * environment variable (comma-separated URLs).
 *
 * Returns a List<String> of service URLs.
 *
 * Example:
 *   def services = new config().getPublicIpServices()
 *   // → ['https://ifconfig.me', 'https://api.ipify.org', 'https://ipinfo.io/ip']
 */
def getPublicIpServices() {
    return getConfigValue('network', 'publicIpServices')
}

