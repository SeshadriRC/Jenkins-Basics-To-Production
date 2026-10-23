# Phase 1: Jenkins CI Foundations & Practical Maven Build

This guide provides a comprehensive, production-grade breakdown of the Phase 1 Declarative Pipeline (`Jenkinsfile.phase1`). It builds a Java Maven application, executes unit tests, packages an executable JAR, and securely archives the artifact with cryptographic fingerprinting.

---

## 1. Architectural Overview

In an enterprise Jenkins architecture, the **Controller (Master)** never runs application builds directly. Its sole responsibility is orchestration, scheduling, and secret management. All workloads run on dedicated **Agents (Workers)**.

```
[ Developer Git Push ]
         │
         ▼
[ Jenkins Controller ] (Docker container: jenkins-controller :8080)
         │  (Dispatches workload via JNLP on port 50000)
         ▼
[ Jenkins Inbound Agent ] (Docker container: jenkins-agent)
   ├── OpenJDK 21 & Maven 3.9 (Build & Test)
   ├── Docker Socket (Container Packaging)
   ├── Trivy CLI (Security Scans)
   ├── SonarQube Scanner (Code Quality)
   ├── Helm 3 (Chart packaging)
   └── Kubectl (Kubernetes Deployments)
```

In our EC2 setup:
- **Controller Container (`jenkins-controller`)**: Runs on port `8080` (HTTP) and `50000` (JNLP inbound port).
- **Agent Container (`jenkins-agent`)**: Connects to the controller over JNLP via the internal Docker bridge network (`devsecops-net`).
- **Agent Label**: `docker-agent`.

---

## 2. Sample Application Details

The sample application is located at `my-practice/app` (Java 21 + Maven):
- **Application Class**: `src/main/java/com/cloudwithvarjosh/App.java` (lightweight HTTP server on port 8080).
- **Unit Test Class**: `src/test/java/com/cloudwithvarjosh/AppTest.java` (JUnit 5 tests).
- **Coverage Plugin**: `jacoco-maven-plugin` configured in `pom.xml` to generate code coverage reports in `target/site/jacoco/`.
- **Packaging Plugin**: `maven-shade-plugin` creates an executable uber-JAR: `target/cwvj-devsecops-demo-1.0.0-SNAPSHOT.jar`.

---

## 3. Overall Pipeline Flow

```
1. Select Agent                 Schedule build on agent matching label 'docker-agent'
       │
       ▼
2. Environment Discovery        Audit host, user UID, Java and Maven versions
       │
       ▼
3. Compile & Unit Test          Execute 'mvn clean test' and publish JUnit XML results
       │
       ▼
4. Package Application          Create shaded JAR with 'mvn package -DskipTests'
       │
       ▼
5. Archive Artifact             Store JAR as immutable build artifact with SHA fingerprint
       │
       ▼
6. Post-Build Actions           Evaluate pipeline status and report success or failure
```

---

## 4. The Complete Phase 1 Pipeline Script (`Jenkinsfile.phase1`)

```groovy
pipeline {
    agent {
        label 'docker-agent'
    }

    options {
        timeout(time: 15, unit: 'MINUTES')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    environment {
        APP_DIR = 'my-practice/app'
    }

    stages {
        stage('Environment Discovery') {
            steps {
                sh '''
                    echo "=== Running on Agent: $(hostname) ==="
                    echo "Current User: $(whoami) (UID: $(id -u))"
                    java -version
                    mvn -version
                '''
            }
        }

        stage('Compile & Unit Test') {
            steps {
                dir("${env.APP_DIR}") {
                    sh 'mvn clean test'
                }
            }
            post {
                always {
                    junit testResults: "${env.APP_DIR}/target/surefire-reports/*.xml",
                          allowEmptyResults: true
                }
            }
        }

        stage('Package Application') {
            steps {
                dir("${env.APP_DIR}") {
                    sh 'mvn package -DskipTests'
                }
            }
        }

        stage('Archive Artifact') {
            steps {
                archiveArtifacts artifacts: "${env.APP_DIR}/target/*.jar",
                                 fingerprint: true
            }
        }
    }

    post {
        success {
            echo "✅ Phase 1 Build #${env.BUILD_NUMBER} completed successfully!"
        }
        failure {
            echo "❌ Phase 1 Build #${env.BUILD_NUMBER} failed."
        }
    }
}
```

---

## 5. Exhaustive Section-by-Section Explanation

### A. `pipeline { }` and `agent { }`

```groovy
pipeline {
    agent {
        label 'docker-agent'
    }
}
```

- `pipeline`: The root block defining the entire declarative pipeline structure.
- `agent`: Specifies where the pipeline stages will execute.
- `label 'docker-agent'`: Directs Jenkins to schedule the job exclusively on an available worker node tagged with the label `docker-agent`.

> **Production Context:** Specifying a label does not launch or provision a container on its own in static agent architectures. An agent registered with this label must already be connected (via SSH or JNLP) to accept jobs. This ensures builds are never run on the Jenkins Controller, protecting the master from resource starvation or untrusted build scripts.

---

### B. `options { }`

```groovy
options {
    timeout(time: 15, unit: 'MINUTES')
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '10'))
}
```

| Directive | Function | Production Rationale |
|---|---|---|
| `timeout(time: 15, unit: 'MINUTES')` | Terminates the entire build if execution exceeds 15 minutes. | Prevents zombie jobs (e.g. hanging network downloads or deadlocks) from indefinitely holding executor slots. |
| `timestamps()` | Prepends ISO/wall-clock timestamps to every console log line. | Essential for post-mortem debugging to detect latency bottlenecks during builds. |
| `buildDiscarder(logRotator(numToKeepStr: '10'))` | Retains only the 10 most recent builds and discards older build logs/artifacts. | Prevents EC2 disk exhaustion on the Jenkins controller `/var/jenkins_home` partition. |

---

### C. `environment { }`

```groovy
environment {
    APP_DIR = 'my-practice/app'
}
```

- Defines pipeline-wide environment variables.
- Accessed across stages via `${env.APP_DIR}`.
- **Why do this?** Adheres to the DRY (Don't Repeat Yourself) principle. If the directory structure changes, you only update this single variable rather than modifying multiple shell steps.

---

### D. Stage 1 — Environment Discovery

```groovy
stage('Environment Discovery') {
    steps {
        sh '''
            echo "=== Running on Agent: $(hostname) ==="
            echo "Current User: $(whoami) (UID: $(id -u))"
            java -version
            mvn -version
        '''
    }
}
```

- `stage()`: Defines a distinct pipeline phase visualized in the Jenkins Blue Ocean / Stage View UI.
- `steps`: Block containing actions to be executed sequentially.
- `sh ''' ... '''`: Executes a multi-line POSIX shell script on the worker agent node.

| Command | Purpose | Why It Matters |
|---|---|---|
| `hostname` | Prints the host or container ID of the executing agent. | Proves which specific container/node accepted the workload. |
| `whoami` & `id -u` | Prints the execution username and numeric UID. | Confirms the build runs as `jenkins` (non-root) to maintain security boundaries. |
| `java -version` | Verifies runtime Java version (Java 21). | Validates JDK parity with application source bytecode target. |
| `mvn -version` | Verifies Apache Maven installation. | Validates build tool availability and maven home path. |

---

### E. Stage 2 — Compile & Unit Test

```groovy
stage('Compile & Unit Test') {
    steps {
        dir("${env.APP_DIR}") {
            sh 'mvn clean test'
        }
    }
    post {
        always {
            junit testResults: "${env.APP_DIR}/target/surefire-reports/*.xml",
                  allowEmptyResults: true
        }
    }
}
```

#### What does `dir()` do?
In a monorepo or sub-directory structure, `dir("${env.APP_DIR}") { ... }` temporarily changes the working directory for enclosed steps to `my-practice/app`, ensuring Maven finds `pom.xml`.

#### What does `mvn clean test` do?
- `clean`: Deletes previous build outputs (the `target/` directory), preventing stale `.class` files or old dependencies from skewing results.
- `test`: Compiles both main sources (`src/main/java`) and test sources (`src/test/java`), then executes all unit tests using the Surefire plugin.

#### What does the stage-level `post { always { ... } }` do?
- `always`: Guarantees this block executes regardless of whether the stage succeeded or failed.
- `junit`: Reads the XML test reports generated in `target/surefire-reports/TEST-*.xml` and renders interactive pass/fail graphs and failure traces in the Jenkins UI.
- `allowEmptyResults: true`: Prevents the JUnit step itself from crashing the pipeline if no test reports are found.

> **Key Rule:** `allowEmptyResults: true` does **not** mask failed unit tests. If a test assertion fails, `mvn clean test` returns exit code `1`, causing the `sh` step (and thus the stage) to fail immediately.

---

### F. Stage 3 — Package Application

```groovy
stage('Package Application') {
    steps {
        dir("${env.APP_DIR}") {
            sh 'mvn package -DskipTests'
        }
    }
}
```

- `package`: Runs the Maven lifecycle through the packaging phase, producing the shaded uber-JAR `cwvj-devsecops-demo-1.0.0-SNAPSHOT.jar` inside `target/`.
- `-DskipTests`: Compiles test code but skips executing tests.

#### Why skip tests here?
Unit tests were already executed in Stage 2. Running them again here would double test execution time and waste CPU cycles. Stage 3 solely handles binary assembly.

---

### G. Stage 4 — Archive Artifact

```groovy
stage('Archive Artifact') {
    steps {
        archiveArtifacts artifacts: "${env.APP_DIR}/target/*.jar",
                         fingerprint: true
    }
}
```

- `archiveArtifacts`: Persists the binary files matching the pattern into the Jenkins Controller storage, making them downloadable from the build dashboard.
- `fingerprint: true`: Computes an md5/sha checksum for the archived JAR.

> **Production Note:** Archiving a JAR in Jenkins provides local build traceability. It does not replace an enterprise artifact registry (like JFrog Artifactory, Nexus, or Docker Registry), which we will introduce in later phases.

---

### H. Pipeline-level `post { }`

```groovy
post {
    success {
        echo "✅ Phase 1 Build #${env.BUILD_NUMBER} completed successfully!"
    }
    failure {
        echo "❌ Phase 1 Build #${env.BUILD_NUMBER} failed."
    }
}
```

- Executes after all stages finish.
- `success`: Fires only if all stages passed cleanly.
- `failure`: Fires if any stage crashed or failed.
- `${env.BUILD_NUMBER}`: Injected environment variable representing the sequential build counter.

---

## 6. Failure Modes & Behavioral Matrix

| Failure Point | Direct Cause | Pipeline Behavior |
|---|---|---|
| **Missing Java / Maven** | Tooling not installed on agent image | Stage 1 exits code 127; pipeline halts; later stages skipped. |
| **Unit Test Assertion Failure** | Bug introduced into `App.java` or `AppTest.java` | Stage 2 exits code 1; JUnit results are still published via `post { always }`; Stages 3 & 4 are skipped. |
| **Compilation Error** | Syntax error in Java source code | Stage 2 fails during `mvn clean test` compile phase; pipeline halts immediately. |
| **Packaging Failure** | Shaded plugin configuration error | Stage 3 fails; Stage 4 (Artifact Archiving) is skipped. |
| **Execution Exceeds 15m** | Hanging process / network hang | `timeout` option aborts the build; marks status as `ABORTED`. |

---

## 7. How to Explain This in a DevOps Interview

> "In our baseline CI pipeline, we implement a declarative Jenkinsfile executing on dedicated, isolated worker agents using JNLP. We enforce pipeline hygiene upfront with strict build timeouts and a 10-build retention policy to protect controller storage. 
> 
> The pipeline follows a fail-fast structure: we first audit the agent environment, then immediately isolate unit testing from packaging using Maven. Running `mvn clean test` independently ensures that any test failure halts the pipeline before burning compute resources on packaging. We parse Surefire XML reports into Jenkins via the JUnit step for visibility. Once tests pass, we compile the final uber-JAR with `-DskipTests` to avoid duplicate test cycles and archive the artifact with cryptographic fingerprinting for end-to-end build traceability."
