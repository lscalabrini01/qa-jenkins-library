/**
 * infrastructure.groovy
 *
 * High-level infrastructure helpers for Jenkins pipelines.
 *
 * Provides utilities for writing configuration files, managing SSH key pairs,
 * generating workspace names, substituting environment variables into content,
 * and managing workspace artifacts.
 *
 * Debug logging
 * -------------
 * Several functions emit extra diagnostic output when debug mode is active.
 * Enable it per-call via the `debug: true` parameter, or globally for a job
 * by setting the environment variable INFRASTRUCTURE_DEBUG=true.
 */

/**
 * Write a configuration file to disk, optionally substituting placeholder
 * variables in the content before writing.
 *
 * Parameters:
 *   path          (String, required) - Destination file path.
 *   content       (String, required) - File content, may contain ${VAR} placeholders.
 *   substitutions (Map,    optional) - Map of placeholder name → replacement value.
 *                                      Replaces every occurrence of ${KEY} in content.
 *
 * Example:
 *   infrastructure.writeConfig(
 *       path: 'config/cattle.yaml',
 *       content: 'host: ${RANCHER_HOST}\ntoken: ${TOKEN}',
 *       substitutions: [RANCHER_HOST: env.RANCHER_URL, TOKEN: env.API_TOKEN]
 *   )
 */
def writeConfig(Map config) {
    if (!(config.path && config.content)) {
        error 'Path and content must be provided.'
    }

    steps.echo "Writing configuration to ${config.path}"

    def processedContent = config.content

    // Apply substitutions if provided
    if (config.substitutions) {
        config.substitutions.each { key, value ->
            processedContent = processedContent.replace("\${${key}}", value.toString())
        }
    }

    try {
        // Ensure directory exists
        def dirPath = config.path.substring(0, config.path.lastIndexOf('/'))
        steps.sh "mkdir -p ${dirPath}"

        steps.writeFile file: config.path, text: processedContent
        steps.echo "Configuration written successfully to ${config.path}"
    } catch (e) {
        error "Failed to write configuration: ${e.message}"
    }
}

/**
 * Decode a base64-encoded SSH private key, write it to disk, derive the
 * corresponding public key via ssh-keygen, and set correct file permissions.
 *
 * Returns the path of the written private key file.
 *
 * Parameters:
 *   keyContent (String,  required) - Base64-encoded private key content
 *                                    (e.g. from a Jenkins secret credential).
 *   keyName    (String,  required) - Filename for the private key (e.g. id_rsa.pem).
 *                                    The public key is written alongside it as
 *                                    <basename>.pub unless pubKeyName is provided.
 *   pubKeyName (String,  optional) - If provided, write the public key to this filename instead of <basename>.pub.
 *   dir        (String,  optional) - Directory to write keys into. Defaults to '.ssh'.
 *   debug      (Boolean, optional) - Emit extra path/directory diagnostics.
 *                                    Also enabled by INFRASTRUCTURE_DEBUG=true.
 *
 * Example:
 *   def keyPath = infrastructure.writeSshKey(
 *       keyContent: env.AWS_SSH_PEM_KEY,
 *       keyName:    env.AWS_SSH_PEM_KEY_NAME,
 *       dir:        '.ssh'
 *   )
 */
def writeSshKey(Map config) {
    if (!(config.keyContent && config.keyName)) {
        error 'SSH key content and name must be provided.'
    }

    def sshDir = config.dir ?: '.ssh'

    // Validate inputs to prevent shell injection via interpolated paths
    if (!config.keyName.matches(/[a-zA-Z0-9._-]+/)) {
        error "SSH key name contains invalid characters (allowed: alphanumeric, dot, underscore, hyphen): ${config.keyName}"
    }
    if (config.pubKeyName && !config.pubKeyName.matches(/[a-zA-Z0-9._-]+/)) {
        error "Public key name contains invalid characters (allowed: alphanumeric, dot, underscore, hyphen): ${config.pubKeyName}"
    }
    if (!sshDir.matches(/[a-zA-Z0-9._\/-]+/)) {
        error "SSH directory path contains invalid characters (allowed: alphanumeric, dot, underscore, hyphen, slash): ${sshDir}"
    }

    steps.echo "Writing SSH key: ${config.keyName}"

    String keyPath = "${sshDir}/${config.keyName}"
    String keyBaseName = config.keyName.replaceAll(/\.[^.]+$/, '')
    String resolvedPubKeyName = config.pubKeyName ?: "${keyBaseName}.pub"
    String pubKeyPath = "${sshDir}/${resolvedPubKeyName}"
    boolean debug = config.debug ?: (env.INFRASTRUCTURE_DEBUG == 'true')

    try {
        steps.withEnv([
            "SSH_DIR=${sshDir}",
            "KEY_PATH=${keyPath}",
            "PUB_KEY_PATH=${pubKeyPath}",
        ]) {
            // Create SSH directory if it doesn't exist
            steps.sh 'mkdir -p "$SSH_DIR"'

            // Decode base64 key content and write private key file
            String decoded = new String(config.keyContent.decodeBase64())

            if (debug) {
                steps.echo '=== SSH Key Configuration ==='
                steps.echo "SSH directory: ${sshDir}"
                steps.echo "Key name: ${config.keyName}"
                steps.echo "Full key path: ${keyPath}"
                steps.echo "Current working directory: ${pwd()}"
            }

            steps.writeFile file: keyPath, text: decoded

            // Verify file was created
            String fileExists = steps.sh(
                script: 'test -f "$KEY_PATH" && echo EXISTS || echo NOT_EXISTS',
                returnStdout: true
            ).trim()

            if (fileExists != 'EXISTS') {
                steps.sh 'ls -la "$SSH_DIR" || echo \'Directory listing failed\''
                error "Failed to create SSH key file at ${keyPath}"
            }

            // Set proper permissions for private key
            steps.sh 'chmod 600 "$KEY_PATH"'

            if (debug) {
                steps.echo '=== Public Key Generation ==='
                steps.echo "Key base name: ${keyBaseName}"
                steps.echo "Public key path: ${pubKeyPath}"
            }

            // Generate public key from private key
            steps.sh 'ssh-keygen -y -f "$KEY_PATH" > "$PUB_KEY_PATH"'

            // Verify public key was created
            String pubFileExists = steps.sh(
                script: 'test -f "$PUB_KEY_PATH" && echo EXISTS || echo NOT_EXISTS',
                returnStdout: true
            ).trim()

            if (pubFileExists != 'EXISTS') {
                steps.sh 'ls -la "$SSH_DIR" || echo \'Directory listing failed\''
                error "Failed to create public key file at ${pubKeyPath}"
            }

            steps.sh 'chmod 644 "$PUB_KEY_PATH"'
        }

        steps.echo "SSH key pair written successfully: ${keyPath} and ${pubKeyPath}"
        return keyPath
    } catch (e) {
        error "Failed to write SSH key: ${e.message}"
    }
}

/**
 * Generate a unique workspace name suitable for use as a Terraform workspace
 * identifier (alphanumeric, hyphens, and underscores only).
 *
 * The name is composed of: <prefix>_<BUILD_NUMBER>[_<suffix>][_<timestamp>]
 *
 * Parameters:
 *   prefix           (String,  optional) - Name prefix. Defaults to 'jenkins_workspace'.
 *   suffix           (String,  optional) - Additional label appended after the build
 *                                          number. Special characters are replaced with
 *                                          hyphens and leading/trailing hyphens stripped.
 *   includeTimestamp (Boolean, optional) - Append a yyyyMMddHHmmss timestamp.
 *                                          Defaults to true.
 *
 * Example:
 *   def wsName = infrastructure.generateWorkspaceName(
 *       prefix: 'rancher_airgap',
 *       suffix: env.CLUSTER_TYPE,
 *       includeTimestamp: true
 *   )
 *   // → rancher_airgap_42_rke2_20260223141500
 */
def generateWorkspaceName(Map config = [:]) {
    def prefix = config.prefix ?: 'jenkins_workspace'
    def buildNumber = env.BUILD_NUMBER ?: 'unknown'

    // Sanitize suffix for Terraform workspace naming (alphanumeric, hyphens, underscores only)
    def sanitizedSuffix = ''
    if (config.suffix) {
        sanitizedSuffix = config.suffix.toString()
            .replaceAll(/[^a-zA-Z0-9-_]/, '-')  // Replace invalid chars with hyphens
            .replaceAll(/-+/, '-')              // Collapse multiple hyphens
            .replaceAll(/^-|-$/, '')            // Remove leading/trailing hyphens
        if (sanitizedSuffix) {
            sanitizedSuffix = "_${sanitizedSuffix}"
        }
    }

    if (config.includeTimestamp != false) {
        def timestamp = new Date().format('yyyyMMddHHmmss')
        return "${prefix}_${buildNumber}${sanitizedSuffix}_${timestamp}"
    }

    return "${prefix}_${buildNumber}${sanitizedSuffix}"
}

/**
 * Replace environment variable references inside a string.
 *
 * Supports both ${VAR} and $VAR syntax. $VAR is only matched when not
 * immediately followed by another identifier character, preventing partial
 * substitution of longer variable names.
 *
 * Parameters:
 *   content (String, required) - Input text containing variable references.
 *   envVars (Map,    optional) - Map of variable name → replacement value.
 *
 * Returns the processed string.
 *
 * Example:
 *   def result = infrastructure.parseAndSubstituteVars(
 *       content: readFile('template.yaml'),
 *       envVars: [CLUSTER_NAME: env.CLUSTER_NAME, REGION: env.AWS_REGION]
 *   )
 */
def parseAndSubstituteVars(Map config) {
    if (!config.content) {
        error 'Content must be provided.'
    }

    def processedContent = config.content

    if (config.envVars) {
        config.envVars.each { key, value ->
            // Replace both ${VAR} and $VAR patterns
            processedContent = processedContent.replaceAll(/\$\{${key}\}/, value.toString())
            processedContent = processedContent.replaceAll(/\$${key}(?![a-zA-Z0-9_])/, value.toString())
        }
    }

    return processedContent
}

/**
 * Auto-detect this runner's public IPv4 address.
 *
 * Queries a primary IP-echo service, falling back to alternates if the
 * first is unreachable, then validates the result is a well-formed IPv4
 * address. Useful for allow-listing the runner's address in ephemeral
 * security group ingress rules (e.g. so Ansible/SSH traffic originating
 * from this runner is permitted).
 *
 * Parameters:
 *   timeout (int, optional) - Per-request max time in seconds. Defaults to 5.
 *
 * Returns the detected public IPv4 address as a String.
 *
 * Example:
 *   env.RUNNER_PUBLIC_IP = infrastructure.detectPublicIp()
 */
def detectPublicIp(Map config = [:]) {
    def timeout = config.timeout ?: 5
    def globalConfig = new config()
    def services = globalConfig.getPublicIpServices()

    def curlChain = services.collect { service ->
        "curl -4 -fsS --max-time ${timeout} ${service}"
    }.join(' ||\n             ')

    def publicIp = steps.sh(
        script: """
            (${curlChain}) | tr -d '[:space:]'
        """,
        returnStdout: true
    ).trim()

    def octets = publicIp.tokenize('.')
    if (octets.size() != 4 || !octets.every { it ==~ /\d{1,3}/ && it.toInteger() <= 255 }) {
        error "Failed to auto-detect a valid public IPv4 address (got: '${publicIp}')"
    }

    steps.echo "Detected runner public IP: ${publicIp}"
    return publicIp
}

/**
 * Extract the directory path from a file path.
 *
 * Given a file path, returns the directory containing the file by extracting
 * everything up to (but not including) the final path separator.
 *
 * Parameters:
 *   filePath (String, required) - The file path to extract the directory from.
 *
 * Returns the directory path (everything before the last '/').
 *
 * Example:
 *   def dir = infrastructure.getDirectory(filePath: 'ansible/k3s/default/site.yml')
 *   // → 'ansible/k3s/default'
 */
def getDirectory(Map config) {
    if (!config.filePath) {
        error 'File path must be provided.'
    }

    def lastSlashIndex = config.filePath.lastIndexOf('/')
    if (lastSlashIndex == -1) {
        return '.'
    }

    return config.filePath.substring(0, lastSlashIndex)
}

/**
 * Create one or more directories in the workspace.
 *
 * Parameters:
 *   paths (List<String>, required) - List of directory paths to create.
 *
 * Example:
 *   infrastructure.createDirectories(paths: ['.ssh', 'config', 'artifacts'])
 */
def createDirectories(Map config) {
    if (!config.paths) {
        error 'Paths must be provided.'
    }

    config.paths.each { path ->
        steps.echo "Creating directory: ${path}"
        steps.sh "mkdir -p ${path}"
    }
}

/**
 * Remove workspace artifacts, swallowing errors for individual paths so that
 * a single missing path does not abort cleanup of the rest.
 *
 * Parameters:
 *   paths (List<String>, required) - List of file/directory paths to remove.
 *   force (Boolean,      optional) - Pass -f to rm. Defaults to false.
 *
 * Example:
 *   infrastructure.cleanupArtifacts(
 *       paths: ['.ssh', 'config/cattle.yaml', 'terraform.tfstate'],
 *       force: true
 *   )
 */
def cleanupArtifacts(Map config) {
    if (!config.paths) {
        error 'Paths must be provided.'
    }

    steps.echo 'Cleaning up artifacts'

    def forceFlag = config.force ? '-f' : ''

    config.paths.each { path ->
        try {
            steps.sh "rm -rf ${forceFlag} ${path}"
            steps.echo "Removed: ${path}"
        } catch (e) {
            steps.echo "Warning: Could not remove ${path}: ${e.message}"
        }
    }
}

/**
 * Write the workspace name to a file and archive it as a Jenkins build
 * artifact so that downstream jobs or stages can retrieve it.
 *
 * Parameters:
 *   workspaceName (String, required) - The workspace name string to persist.
 *   fileName      (String, optional) - Artifact filename. Defaults to 'workspace_name.txt'.
 *
 * Example:
 *   infrastructure.archiveWorkspaceName(workspaceName: wsName)
 */
def archiveWorkspaceName(Map config) {
    if (!config.workspaceName) {
        error 'Workspace name must be provided.'
    }

    def fileName = config.fileName ?: 'workspace_name.txt'

    steps.echo "Archiving workspace name: ${config.workspaceName}"

    try {
        steps.writeFile file: fileName, text: config.workspaceName
        steps.archiveArtifacts artifacts: fileName, fingerprint: true
        steps.echo "Workspace name archived to ${fileName}"
    } catch (e) {
        error "Failed to archive workspace name: ${e.message}"
    }
}

/**
 * Read a previously archived workspace name from a build artifact file.
 *
 * Parameters:
 *   fileName (String, optional) - Artifact filename to read. Defaults to 'workspace_name.txt'.
 *
 * Returns the workspace name string (trimmed).
 *
 * Example:
 *   def wsName = infrastructure.getArchivedWorkspaceName()
 */
def getArchivedWorkspaceName(Map config = [:]) {
    def fileName = config.fileName ?: 'workspace_name.txt'

    steps.echo "Retrieving archived workspace name from ${fileName}"

    try {
        def workspaceName = steps.readFile(fileName).trim()
        steps.echo "Retrieved workspace name: ${workspaceName}"
        return workspaceName
    } catch (e) {
        error "Failed to retrieve workspace name: ${e.message}"
    }
}
