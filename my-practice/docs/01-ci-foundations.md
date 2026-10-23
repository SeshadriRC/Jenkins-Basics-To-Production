# Phase 1: Jenkins CI Foundations & Practical Maven Build

## 1. Architectural Overview

In a production Jenkins setup, the **Controller (Master)** should never execute application builds directly. Its sole responsibility is orchestration, scheduling, and secret management. All workloads run on dedicated **Agents (Workers)**.

In our EC2 setup:
- **Controller Container (`jenkins-controller`)**: Runs on port `8080` (HTTP) and `50000` (JNLP inbound port).
- **Agent Container (`jenkins-agent`)**: Connects to the controller over JNLP via the internal Docker bridge network (`devsecops-net`).
- **Agent Label**: `docker-agent` (or `docker-agent-devsecops`).
- **Pre-installed tools on Agent**:
  - OpenJDK 21
  - Apache Maven 3.9
  - Docker CLI
  - Trivy
  - Helm 3
  - Kubectl

---

## 2. Sample Application Details

The sample application is located in `my-practice/app` (Java 21 + Maven).
- **Application class**: `src/main/java/com/cloudwithvarjosh/App.java`
- **Unit test**: `src/test/java/com/cloudwithvarjosh/AppTest.java`
- **Coverage Plugin**: `jacoco-maven-plugin` configured in `pom.xml` to generate code coverage reports in `target/site/jacoco/`.
- **Packaging Plugin**: `maven-shade-plugin` produces an executable uber-JAR: `target/cwvj-devsecops-demo-1.0.0-SNAPSHOT.jar`.

---

## 3. Phase 1 Pipeline: Step-by-Step

Our first goal is to build a rock-solid, declarative CI pipeline that does 4 things:
1. **Dispatches execution** to the `docker-agent`.
2. **Compiles and executes unit tests** using Maven.
3. **Packages the executable JAR**.
4. **Archives the built artifact** inside Jenkins with SHA fingerprints.

### `Jenkinsfile.phase1`

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
        stage('Environment & Tooling Verification') {
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
                    // Record JUnit test results in Jenkins UI
                    junit testResults: "${env.APP_DIR}/target/surefire-reports/*.xml", allowEmptyResults: true
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
                archiveArtifacts artifacts: "${env.APP_DIR}/target/*.jar", fingerprint: true
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

## 4. Key Production Concepts Explained

### A. Agent Isolation & Directory Context (`dir`)
When Jenkins runs a multi-directory repo, executing commands inside the specific subproject path using `dir('my-practice/app')` ensures that Maven works with the relevant `pom.xml` without polluting or referencing parent directories.

### B. Separating `mvn test` and `mvn package`
In professional CI pipelines, we separate unit testing from final packaging:
- **Fail Fast**: If unit tests break, the pipeline terminates immediately without wasting compute cycles generating shaded JARs or Docker images.
- **Test Reporting**: The `junit` post-step parses standard Surefire XML reports and integrates interactive test trend graphs directly into the Jenkins UI.

### C. Artifact Archiving with Fingerprinting
`archiveArtifacts artifacts: '...', fingerprint: true` stores the immutable binary in the Jenkins master storage and tracks its cryptographic hash. This ensures traceability: you can always identify which exact build produced a specific JAR file running in production.

---

## 5. Next Steps

Once we verify Phase 1 successfully runs and archives the JAR:
- **Phase 2**: Introduce SonarQube static code analysis (SAST) and enforce Quality Gate thresholds.
