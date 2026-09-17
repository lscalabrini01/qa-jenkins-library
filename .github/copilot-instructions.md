# QA Jenkins Library - Copilot Code Review Instructions

This is a **Jenkins Global Shared Library** written in Groovy. It standardizes CI/CD pipelines across the Rancher QA organization by abstracting infrastructure provisioning, Docker container lifecycle, test execution, Ansible deployment, and OpenTofu/Terraform operations into reusable pipeline functions. Once imported via `library 'qa-jenkins-library'` in a Jenkinsfile, any repo can call these functions (e.g. `container.build(...)`, `project.checkout(...)`) instead of duplicating scripted pipeline logic.

## Build, Test, and Lint

Gradle wrapper (`gradlew`/`gradlew.bat`) with the `groovy` plugin; no `src/main`, only `src/test/groovy`.

- Run all tests: `./gradlew test` (Windows: `gradlew.bat test`)
- Run a single test class: `./gradlew test --tests "InfrastructureScriptTest"`
- Run a single test method: `./gradlew test --tests "InfrastructureScriptTest.methodName"`
- Dependencies are version-locked (`gradle.lockfile` via `dependencyLocking`) — after changing `build.gradle` dependencies, regenerate locks with `./gradlew dependencies --write-locks`
- Test stack: JUnit 5 (Jupiter) + JenkinsPipelineUnit (`BasePipelineTest`) + AssertJ; Spock (`spock-core`) is also on the test classpath
- There is no separate lint task; Groovy style is enforced only through code review against the conventions below

## Architecture

- **`vars/*.groovy`** — All public pipeline functions (Jenkins "global variables"). Current modules: `airgap`, `ansible`, `config`, `container`, `generate`, `infrastructure`, `make`, `naming`, `project`, `property`, `result`, `tofu`.
- **`src/test/groovy/*.groovy`** — Unit tests using JenkinsPipelineUnit + JUnit 5 + AssertJ, one `*ScriptTest.groovy` per `vars` module, all extending `BasePipelineTest`
- No `src/main` — the library is purely `vars/` scripts
- Functions access Jenkins context through implicit bindings: `env`, `steps`, `pwd()`, `sh()`, `echo()`, `error()`, etc.
- `BasePipelineTest` (in `src/test/groovy`) sets `scriptRoots = ['vars']` and seeds default `env` values (`BUILD_NUMBER`, `JOB_NAME`, `WORKSPACE`) so most tests don't need to set them manually

## Code Conventions

### Functions use named Map parameters

```groovy
// CORRECT
def runPlaybook(Map config) { ... }
def generateNames(Map params = [:]) { ... }

// WRONG — don't use positional parameters
def runPlaybook(String playbook, String inventory) { ... }
```

### Validate required parameters at function entry

```groovy
def doSomething(Map config) {
    if (!(config.dir && config.name)) {
        error 'Directory and name must be provided.'
    }
    // ...
}
```

### Private helpers use underscore prefix

```groovy
def _getImage() { ... }
def _containerCommand(Map container) { ... }
```

### Cross-module calls use `new`

```groovy
def config = new config()
def infra = new infrastructure()
new tofu().teardownInfrastructure(config)
```

### Docker commands run in containers

Commands are executed via `docker run --rm` with workspace mounted at `/workspace`:

```groovy
def cmd = "docker run --rm --platform ${platform} ${envArgs} -v ${workspace}:/workspace -w /workspace ${image} sh -c \"${command}\""
steps.sh(script: cmd, returnStatus: true)
```

## Documentation Standards

Every public function must have a Javadoc-style comment with:

```groovy
/**
 * Brief description of what the function does.
 *
 * Parameters:
 *   dir (String, required) — Working directory
 *   name (String, optional, default: 'default') — Resource name
 *
 * Returns:
 *   Map with keys [container, image]
 *
 * Example:
 *   def result = container.build(dir: 'build', name: 'my-image')
 */
```

Every `vars/*.groovy` file starts with a module-level comment block containing filename, purpose description, and a usage/workflow example.

## Security

- Validate user-supplied strings before interpolating into shell commands
- Use regex validation for paths: `config.keyName.matches(/[a-zA-Z0-9._-]+/)`
- Escape backslashes and quotes for shell args: `arg.replace('\\', '\\\\').replace('"', '\\"')`
- Never hardcode credentials — use `steps.withCredentials` or `steps.withFolderProperties`

## Test Conventions

- All tests extend `BasePipelineTest` (in `src/test/groovy/`)
- Use JUnit 5 annotations: `@Test`, `@BeforeEach`, `@DisplayName`
- Use AssertJ assertions: `assertThat(actual).isEqualTo(expected)`
- Mock `error()` to throw `RuntimeException` so assertions can catch it
- Mock `steps` via `metaClass` on a plain `Object`
- Organize tests by function with section headers

## Review Priorities

When reviewing code in this repo, prioritize:

1. **Shell injection** — ensure user input is validated/escaped before interpolation into shell commands
2. **Parameter validation** — every function must validate required parameters
3. **Documentation** — all public functions need Javadoc with Parameters/Returns/Example
4. **Error handling** — use `error()` for fatal issues, `steps.echo` for warnings
5. **Naming conventions** — underscore prefix for private helpers, Map params for public functions
6. **Test coverage** — new functions need tests in `src/test/groovy/`

For a structured, repeatable review against all of the above (with a
severity-tagged findings table), use the `code-review` skill at
[`.github/skills/code-review/SKILL.md`](skills/code-review/SKILL.md).
