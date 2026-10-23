# Phase 2: Static Application Security Testing (SAST) & SonarQube Quality Gates

This guide provides a comprehensive, production-grade breakdown of the Phase 2 Declarative Pipeline (`Jenkinsfile.phase2`). It integrates **SonarQube** static analysis, security hotspot detection, JaCoCo code coverage ingestion, and strict **Quality Gate** enforcement into our CI pipeline.

---

## 1. Architectural Overview & Network Topology

In modern DevSecOps, security scanning must happen early ("Shift-Left") within the CI pipeline. SonarQube runs as an autonomous service with an attached PostgreSQL database.

```
[ Developer Commit ]
         │
         ▼
[ Jenkins Controller ] (Container: jenkins-controller :8080)
         │  (Dispatches workload via JNLP)
         ▼
[ Jenkins Inbound Agent ] (Container: jenkins-agent)
   ├── 1. Compile & Unit Test with JaCoCo Coverage
   │      └── Produces target/site/jacoco/jacoco.xml
   │
   ├── 2. Execute SonarQube Scanner for Maven
   │      │   Communicates over Docker network 'devsecops-net'
   │      ▼
   │   [ SonarQube Server ] (Container: sonarqube :9000 internal / :8090 host)
   │      └── Evaluates AST, Rulesets, Hotspots, and Quality Gate
   │      ▲
   │      └── Backed by PostgreSQL (Container: sonarqube-db :5432)
   │
   └── 3. Quality Gate Evaluation (-Dsonar.qualitygate.wait=true)
          ├── IF PASS: Proceed to Package & Archive
          └── IF FAIL: Abort Pipeline immediately (Fail-Fast)
```

### Network Communication & Port Routing

| Component | Container Name | Internal Port (`devsecops-net`) | Host / Public Port | Accessible By |
|---|---|---|---|---|
| **Jenkins Controller** | `jenkins-controller` | `8080`, `50000` (JNLP) | `8080`, `50000` | Developer Web Browser, Jenkins Agent |
| **Jenkins Agent** | `jenkins-agent` | Dynamic outbound | None | Connects to Controller via JNLP |
| **SonarQube Server** | `sonarqube` | `9000` | `8090` | `jenkins-agent` (via `sonarqube:9000`), Developer (via `http://<EC2-IP>:8090`) |
| **Sonar PostgreSQL** | `sonarqube-db` | `5432` | None (Internal) | `sonarqube` container |

> [!IMPORTANT]
> **Container Networking Rule:** When `jenkins-agent` communicates with SonarQube during the build, it MUST use `http://sonarqube:9000` (the Docker DNS service name on `devsecops-net`). It cannot use `http://localhost:8090` or `http://localhost:9000` because `localhost` inside a container refers to the container itself, not the host machine!

---

## 2. SAST Fundamentals & Shift-Left Philosophy

### Where SAST Fits in the Security Hierarchy

| Security Discipline | Tool Example | When It Runs | What It Analyzes |
|---|---|---|---|
| **SAST (Static Application Security Testing)** | **SonarQube** | Build time (Pre-packaging) | Source code, Abstract Syntax Tree (AST), coding anti-patterns, security hotspots |
| **SCA (Software Composition Analysis)** | **Trivy / OWASP Dependency-Check** | Build / Containerization | Third-party open-source dependencies (CVEs in `pom.xml` / JARs) |
| **Container Scanning** | **Trivy / Clair** | Post-packaging | Operating system base image packages and filesystem vulnerabilities |
| **DAST (Dynamic Application Security Testing)** | **OWASP ZAP** | Post-deployment (Staging/QA) | Running application HTTP endpoints, XSS, CSRF, auth bypass |

### SonarQube Issue Classifications

1. **Bug**: An error in code that will break functionality or cause runtime exceptions (e.g. comparing Strings using `==` instead of `.equals()`).
2. **Vulnerability**: A demonstrable security weakness that can be directly exploited by an attacker (e.g. SQL injection, path traversal, hardcoded RSA private keys).
3. **Security Hotspot**: Security-sensitive code that may or may not be vulnerable depending on context. It requires manual human review to confirm whether security controls are in place (e.g. hardcoded API keys, unencrypted HTTP servers).
4. **Code Smell**: Maintainability and clean-code issues that make code difficult to read, refactor, or test (e.g. unclosed streams, `System.out.println` instead of a logging framework).

---

## 3. Application Security Audit: Findings in Our Sample Code

Our sample application (`my-practice/app/src/main/java/com/cloudwithvarjosh/App.java`) includes deliberate educational patterns for SonarQube to analyze:

```java
// Finding 1: Hardcoded Secret (Security Hotspot)
private static final String API_KEY = "CVWJ_DEMO_SECRET";

// Finding 2: Using System.out instead of standard logger (Code Smell - Maintainability)
System.out.println("Starting CVWJ DevSecOps Demo...");

// Finding 3: Scanner resource leak (Code Smell - Resource Management)
Scanner sc = new Scanner(System.in);

// Finding 4: Inappropriate String comparison using == (Bug - Reliability)
if (username == "admin") { 
    System.out.println("Welcome, admin!");
}
```

When SonarQube analyzes this repository:
- **Security Hotspot:** Flags `API_KEY` for manual security triage.
- **Bug:** Flags `username == "admin"` because `==` tests object identity rather than value equality.
- **Code Smell:** Flags `System.out.println` and recommend `org.slf4j.Logger`.
- **Code Coverage:** JaCoCo reports line and branch coverage generated from `AppTest.java`.

---

## 4. The Complete Phase 2 Pipeline Script (`Jenkinsfile.phase2`)

```groovy
pipeline {
    agent {
        label 'docker-agent'
    }

    options {
        timeout(time: 20, unit: 'MINUTES')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    environment {
        APP_DIR = 'my-practice/app'
        SONAR_HOST_URL = 'http://sonarqube:9000'
        SONAR_PROJECT_KEY = 'cwvj-devsecops-demo'
        SONAR_PROJECT_NAME = 'CVWJ DevSecOps Demo'
    }

    stages {
        stage('Environment Discovery') {
            steps {
                sh '''
                    echo "=== Running on Agent: $(hostname) ==="
                    echo "Current User: $(whoami) (UID: $(id -u))"
                    java -version
                    mvn -version
                    echo "=== Auditing SonarQube Reachability ==="
                    curl -sI ${SONAR_HOST_URL}/api/system/status || true
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

        stage('SonarQube SAST & Quality Gate') {
            steps {
                dir("${env.APP_DIR}") {
                    withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                        sh '''
                            mvn sonar:sonar \
                              -Dsonar.projectKey="${SONAR_PROJECT_KEY}" \
                              -Dsonar.projectName="${SONAR_PROJECT_NAME}" \
                              -Dsonar.host.url="${SONAR_HOST_URL}" \
                              -Dsonar.token="${SONAR_TOKEN}" \
                              -Dsonar.coverage.jacoco.xmlReportPaths="target/site/jacoco/jacoco.xml" \
                              -Dsonar.qualitygate.wait=true
                        '''
                    }
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
            echo "✅ Phase 2 Build #${env.BUILD_NUMBER} passed all Quality Gates and completed successfully!"
        }
        failure {
            echo "❌ Phase 2 Build #${env.BUILD_NUMBER} failed. Review JUnit test results or SonarQube Quality Gate status."
        }
    }
}
```

---

## 5. In-Depth Pipeline & Parameter Breakdown

### A. Secret Injection with `withCredentials`

```groovy
withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
    ...
}
```

- `withCredentials`: Secure step provided by the Jenkins `credentials-binding` plugin.
- `credentialsId: 'sonarqube-token'`: Matches the identifier of the secret text stored in the Jenkins credential store.
- `variable: 'SONAR_TOKEN'`: Injects the secret value into an environment variable available **only** within the enclosed Groovy closure.
- **Log Masking:** Jenkins automatically intercepts standard output and console logs, replacing any occurrence of the token value with `****` to prevent credential exposure in build logs.

---

### B. Maven Sonar Scanner Parameters

```bash
mvn sonar:sonar \
  -Dsonar.projectKey="${SONAR_PROJECT_KEY}" \
  -Dsonar.projectName="${SONAR_PROJECT_NAME}" \
  -Dsonar.host.url="${SONAR_HOST_URL}" \
  -Dsonar.token="${SONAR_TOKEN}" \
  -Dsonar.coverage.jacoco.xmlReportPaths="target/site/jacoco/jacoco.xml" \
  -Dsonar.qualitygate.wait=true
```

| Parameter | Purpose | Production Significance |
|---|---|---|
| `-Dsonar.projectKey` | Unique identifier for the project in SonarQube (`cwvj-devsecops-demo`). | Used by SonarQube to group historical scans, track trendlines, and enforce project-specific quality gates. |
| `-Dsonar.projectName` | Human-readable name displayed in the SonarQube UI. | Provides clear identification in multi-tenant SonarQube dashboards. |
| `-Dsonar.host.url` | Target endpoint of SonarQube (`http://sonarqube:9000`). | Uses the internal Docker network name for low-latency, secure communication between agent and scanner. |
| `-Dsonar.token` | Authentication token generated by SonarQube. | Eliminates the need for hardcoded passwords and supports token revocation without changing user credentials. |
| `-Dsonar.coverage.jacoco.xmlReportPaths` | Path to the JaCoCo XML coverage report generated in Stage 2. | Ingests line and branch test coverage metrics directly into SonarQube's Quality Gate computation. |
| `-Dsonar.qualitygate.wait=true` | Forces the Maven scanner to synchronously wait for the Quality Gate result. | **The Quality Gate Enforcer:** If the gate fails (e.g. coverage below target or new bugs found), Maven exits with code `1`, immediately halting the pipeline. |

---

### C. Why `-Dsonar.qualitygate.wait=true` Over Jenkins Webhooks?

There are two primary ways to enforce Quality Gates in Jenkins:

1. **Method 1 (Native Maven Parameter): `-Dsonar.qualitygate.wait=true` (Used Here)**
   - The Maven Sonar plugin itself polls the SonarQube server's Compute Engine task until the analysis completes and the Quality Gate evaluation is finalized.
   - **Advantages:**
     - Requires **zero extra Jenkins plugins** (no SonarQube scanner plugin or webhook setup required).
     - Immune to network routing issues between SonarQube and Jenkins (such as NAT, reverse proxies, or firewall boundaries).
     - Portable: Works identically in Jenkins, GitHub Actions, GitLab CI, or local developer terminals.

2. **Method 2 (Jenkins Webhook + `waitForQualityGate()` step)**
   - Jenkins executes `withSonarQubeEnv` and triggers the scan. SonarQube later sends an HTTP POST webhook back to Jenkins at `http://<jenkins>:8080/sonarqube-webhook/`.
   - **Trade-off:** If the webhook URL is misconfigured, blocked by a firewall, or fails to reach Jenkins, the pipeline hangs until timeout.

---

## 6. Quality Gate Mechanics & Failure Behavior

### The Fail-Fast Principle

The Quality Gate acts as an automated security boundary:

```
[ Stage: Compile & Test ] ──► Passed ✅
            │
            ▼
[ Stage: SonarQube SAST ] ──► Quality Gate Evaluation
                                   │
                  ┌────────────────┴────────────────┐
                  ▼                                 ▼
             [ Gate PASSED ]                   [ Gate FAILED ]
                  │                                 │
                  ▼                                 ▼
       [ Package Application ]             Pipeline Aborted ❌
                  │                        - Packaging SKIPPED
                  ▼                        - Archiving SKIPPED
       [ Archive Artifacts ]               - Build marked FAILURE
```

---

### The SonarQube Governance Triad: Profiles vs Gates vs Coverage

To master code governance in SonarQube, you must understand how these three independent concepts interact:

```
┌──────────────────────────────────────────────────────────┐
│ 1. QUALITY PROFILE (The Rulebook)                        │
│    "What code patterns and vulnerabilities are flagged?" │
│    - Defines active rules (Bugs, Vulnerabilities, Smells)│
│    - Sets rule severities (Blocker, Critical, Major, etc)│
└────────────────────────────┬─────────────────────────────┘
                             │ (Scans Source Code & Tests)
                             ▼
┌──────────────────────────────────────────────────────────┐
│ 2. CODE COVERAGE & METRICS (The Measurement)             │
│    "How thoroughly is the code tested?"                  │
│    - JaCoCo generates line and branch execution logs     │
│    - Scanner computes metrics: Coverage (3.2%), Bugs (1) │
└────────────────────────────┬─────────────────────────────┘
                             │ (Submits Metrics to Gate)
                             ▼
┌──────────────────────────────────────────────────────────┐
│ 3. QUALITY GATE (The Enforcer)                           │
│    "Does the code meet release criteria?"                │
│    - Compares measured metrics against Boolean thresholds│
│    - Evaluates PASS (OK) or FAIL (ERROR)                 │
│    - Halts or approves CI/CD deployment                  │
└──────────────────────────────────────────────────────────┘
```

#### 1. Quality Profiles (The Rulebook)
- A **Quality Profile** is a collection of static analysis rules activated for a specific language (Java, XML, Python, JavaScript).
- When SonarQube parses code into an Abstract Syntax Tree (AST), it compares the tree against the rules enabled in the active profile.
- SonarQube ships with default profiles like `Sonar way` for Java (containing ~450+ standard rules).
- **Custom Profiles:** Organizations often create child profiles (e.g. `Enterprise Java Security Profile`) to:
  - Promote certain rules to **Blocker** (e.g., forbidding `System.out.println` or SQL string concatenation).
  - Activate specialized compliance rules (OWASP Top 10, CWE Top 25).
  - Deactivate rules that produce noise or false positives for their specific framework.

#### 2. Code Coverage (The Measurement)
- **What is Code Coverage?** It measures the percentage of executable lines and branches tested by unit tests.
- **Why was Build #1's coverage only 3.2%?**
  In [`AppTest.java`](../app/src/test/java/com/cloudwithvarjosh/AppTest.java), only the helper method `brandHtml()` is exercised. The `main()` method (containing `HttpServer`, `Scanner`, and string comparison) was never called during unit tests. Out of 27 coverable lines, only 1 was executed:
  $$\text{Coverage} = \frac{1 \text{ covered line}}{27 \text{ lines}} \approx 3.7\% \text{ line coverage (3.2\% overall)}$$
- **How JaCoCo Works:**
  1. `jacoco-maven-plugin:prepare-agent` passes a `-javaagent` flag to the JVM during `mvn clean test`.
  2. The agent monitors class loading and instruction execution, dumping binary counters into `target/jacoco.exec`.
  3. The `report` goal transforms `jacoco.exec` into human-readable HTML and machine-readable `target/site/jacoco/jacoco.xml`.
  4. The SonarQube scanner ingests `jacoco.xml` via `-Dsonar.coverage.jacoco.xmlReportPaths` and populates the project coverage metric.

#### 3. Quality Gates (The Enforcer: New Code vs Overall Code)
A Quality Gate is a set of Boolean conditions (e.g. `Coverage < 80%`, `Bugs > 0`).

> [!IMPORTANT]
> **Why did Build #1 Pass when Coverage was only 3.2%?**
> SonarQube follows the **Clean as You Code** paradigm. The built-in `Sonar way` Quality Gate only enforces conditions on **"New Code"**:
> - `Coverage on New Code < 80%`
> - `Duplicated Lines on New Code > 3%`
> - `New Issues / Violations > 0`
> - `Security Hotspots Reviewed on New Code < 100%`
>
> On the very first analysis of a branch or project, SonarQube establishes a **baseline**. Because no prior commit existed on SonarQube, there was **0 lines of New Code**! Since all 27 lines were treated as baseline "Overall Code", 0 conditions failed, and SonarQube reported `QUALITY GATE STATUS: PASSED (OK)`.

<img width="1917" height="846" alt="image" src="https://github.com/user-attachments/assets/a65f5aea-e43a-487d-b1fa-9136c4f986d0" />


---

### How to Make the Pipeline FAIL on Low Code Coverage

In an enterprise pipeline, you can enforce coverage thresholds at two independent layers:

#### Method A: Enforce via SonarQube Quality Gate (SAST Layer)
We configure a Quality Gate condition on **Overall Code**:

1. Go to SonarQube: `http://43.204.100.125:8090/quality_gates`.
2. Notice our custom gate: **`Strict DevSecOps Gate`**.
3. It contains two conditions:
   - **Overall Code**: `Coverage is less than 80.0%`
   - **New Code**: `Coverage on New Code is less than 80.0%`
4. Associated Project: `cwvj-devsecops-demo`.

**Execution Result in Jenkins:**
When the pipeline runs with `-Dsonar.qualitygate.wait=true`:
1. The Maven scanner finishes scanning and sends `jacoco.xml` (3.2% coverage).
2. SonarQube evaluates `Strict DevSecOps Gate`.
3. The condition `Coverage is less than 80.0%` **FAILS** (3.2% < 80%).
4. SonarQube returns `status: ERROR`.
5. Maven throws a fatal exception:
   ```text
   [INFO] QUALITY GATE STATUS: FAILED - View details on http://sonarqube:9000/dashboard?id=cwvj-devsecops-demo
   [ERROR] Failed to execute goal org.sonarsource.scanner.maven:sonar-maven-plugin:...:sonar: QUALITY GATE STATUS: FAILED
   ```
6. Jenkins marks Stage 3 (`SonarQube SAST & Quality Gate`) as **FAILED**.
7. Downstream stages (`Package Application` and `Archive Artifact`) are **ABORTED**.

<img width="1912" height="483" alt="image" src="https://github.com/user-attachments/assets/2901e5c3-b647-4b24-968b-1105cefe1beb" />

<img width="1908" height="872" alt="image" src="https://github.com/user-attachments/assets/2adc5895-4dbd-4604-ae3b-8c35fd5fb97a" />


---

#### Method B: Enforce via Maven JaCoCo `jacoco:check` (Pre-Sonar Build Layer)
If you want the build to fail fast during the local Maven compile/test phase before even uploading to SonarQube, configure `jacoco:check` in `pom.xml`:

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>${jacoco.version}</version>
  <executions>
    <execution>
      <goals><goal>prepare-agent</goal></goals>
    </execution>
    <execution>
      <id>report</id>
      <phase>test</phase>
      <goals><goal>report</goal></goals>
    </execution>
    <execution>
      <id>jacoco-check</id>
      <phase>test</phase>
      <goals><goal>check</goal></goals>
      <configuration>
        <rules>
          <rule>
            <element>BUNDLE</element>
            <limits>
              <limit>
                <counter>LINE</counter>
                <value>COVEREDRATIO</value>
                <minimum>0.80</minimum> <!-- Requires 80% coverage -->
              </limit>
            </limits>
          </rule>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

When `mvn clean test` runs with this rule:
```text
[ERROR] Coverage checks have failed: Rule violated for bundle 'CVWJ DevSecOps Demo': lines covered ratio is 0.03, minimum is 0.80
```
This halts the build at Stage 2 before wasting CPU and compute time on static analysis.

---

## 7. Senior DevOps Interview Preparation

### Q1: What is the architectural purpose of a SonarQube Quality Gate in a CI/CD pipeline?
> **Answer:** A Quality Gate serves as an automated policy gate that prevents substandard or insecure code from progressing through the deployment pipeline. Rather than treating static analysis as an informational report, the Quality Gate establishes non-negotiable pass/fail conditions (such as 0 new high-severity bugs, 0 new vulnerabilities, 80% coverage on new code). If these conditions are violated, the pipeline fails immediately (fail-fast), ensuring that downstream packaging and deployment stages never produce or deploy vulnerable artifacts.

---

### Q2: How do you handle SonarQube token authentication securely in a declarative Jenkins pipeline?
> **Answer:** We never hardcode credentials or tokens in pipeline scripts or version control. We store the token in Jenkins' credential store as a `Secret text` credential. In the declarative pipeline, we use the `withCredentials` binding step to inject the secret into an isolated environment variable (`SONAR_TOKEN`) only within the scope of the scanning stage. Jenkins automatically masks the token in console output with asterisks (`****`) to prevent credential leakage.

---

### Q3: What is the difference between a SonarQube Security Hotspot and a Vulnerability?
> **Answer:** A **Vulnerability** is a confirmed security defect in code with a clear path to exploitation (e.g., an unescaped SQL statement vulnerable to injection). It requires immediate remediation. A **Security Hotspot** is security-sensitive code (such as cryptographic configurations, authentication helpers, or hardcoded strings that look like credentials) that may be completely safe depending on architecture and operational context. A Security Hotspot requires a human security review to inspect the context and mark it as either *Reviewed (Safe)* or *Acknowledged (To Fix)* before the Quality Gate can pass.

---

### Q4: Why did we connect the agent to SonarQube over `http://sonarqube:9000` rather than `http://localhost:8090`?
> **Answer:** In our containerized topology, the Jenkins agent and SonarQube execute in separate Docker containers connected via a custom bridge network (`devsecops-net`). Inside the agent container, `localhost` refers strictly to the agent container's own network namespace. SonarQube is published to the host on port `8090` for external browser access, but internally on the Docker bridge network, it listens on port `9000`. Using Docker's built-in DNS service discovery, the agent container resolves `sonarqube:9000` directly and securely without traversing the host network or public interfaces.

---

## 8. Jenkins UI & SonarQube Hands-On Practical Walkthrough

Follow these exact steps to execute Phase 2 in your environment.

### Step 1: Access SonarQube Web Console
1. Open your browser and navigate to SonarQube:
   ```text
   http://43.204.100.125:8090
   ```
2. Log in with your SonarQube credentials (username: `admin`).

---

### Step 2: Generate a SonarQube Security Token
1. In the upper-right corner of the SonarQube dashboard, click your **User Avatar** (circle icon with your initial) and select **My Account**.
2. Click the **Security** tab.
3. In the **Generate New Token** section:
   - **Name:** `jenkins-sonar-token`
   - **Type:** `User Token` (or `Global Analysis Token`)
   - **Expires in:** `30 days` (or `No expiration` for lab)
4. Click **Generate**.
5. **Copy the generated token immediately** (it looks like `sqa_...` or `squ_...`). SonarQube will never show it again!

---

### Step 3: Add the Token to Jenkins Credentials
1. Open your browser and navigate to Jenkins:
   ```text
   http://43.204.100.125:8080
   ```
2. On the left sidebar, click **Manage Jenkins**.
3. Under the **Security** section, click **Credentials**.
4. In the table, click the **(global)** domain link (under **Stores scoped to Jenkins** -> **System**).
5. On the left sidebar, click **Add Credentials**.
6. Fill in the credential form:
   - **Kind:** Select **`Secret text`** from the dropdown.
   - **Scope:** `Global (Jenkins, nodes, items, all child items, etc.)`
   - **Secret:** Paste your SonarQube token copied in Step 2.
   - **ID:** Enter exactly:
     ```text
     sonarqube-token
     ```
     > [!IMPORTANT]
     > The ID must match `credentialsId: 'sonarqube-token'` in `Jenkinsfile.phase2` exactly (case-sensitive).
   - **Description:** `SonarQube Analysis Token for CI Pipelines`
7. Click **Create**.

---

### Step 4: Configure or Create the Phase 2 Jenkins Job
You can either create a new job or update your existing Phase 1 job:

#### Option A: Create a New Job (`devsecops-pipeline-phase2`)
1. Click **New Item** on the Jenkins home page.
2. Enter name: `devsecops-pipeline-phase2`.
3. Select **Pipeline** and click **OK**.
4. Scroll down to the **Pipeline** configuration section.
5. **Definition:** Select `Pipeline script from SCM`.
6. **SCM:** Select `Git`.
7. **Repository URL:** `https://github.com/SeshadriRC/Jenkins-Basics-To-Production.git`
8. **Credentials:** `- none -`
9. **Branch Specifier:** `*/main`
10. **Script Path:** Enter:
    ```text
    my-practice/Jenkinsfile.phase2
    ```
11. Click **Save**.

#### Option B: Update Existing Job
1. Click on `devsecops-pipeline-phase1` -> click **Configure**.
2. Scroll to **Pipeline** -> change **Script Path** to `my-practice/Jenkinsfile.phase2`.
3. Click **Save**.

---

### Step 5: Execute the Build & Verify Live Execution
1. Click **Build Now** on the left menu.
2. Click on the new build number in **Build History** -> click **Console Output**.
3. Observe the sequential stage execution:
   - **Environment Discovery:** Audits host, tools, and verifies SonarQube connectivity (`HTTP 200/405 UP`).
   - **Compile & Unit Test:** Compiles code, runs JUnit tests, and generates `target/site/jacoco/jacoco.xml`.
   - **SonarQube SAST & Quality Gate:**
     - Masked secret injected: `withCredentials([****])`.
     - Analyzes Java AST, checks rulesets, reads JaCoCo XML report.
     - Submits analysis to SonarQube server.
     - Waits for Compute Engine processing (`-Dsonar.qualitygate.wait=true`).
     - Reports Quality Gate status.
   - **Package Application:** Packages uber-JAR.
   - **Archive Artifact:** Archives JAR with SHA fingerprint.

---

### Step 6: Inspect Findings in SonarQube Dashboard
1. Open `http://43.204.100.125:8090/dashboard?id=cwvj-devsecops-demo`.
2. Inspect the project overview metrics:
   - **Quality Gate:** Green (Passed) or Red (Failed).
   - **Bugs:** Notice the detected String comparison bug in `App.java`.
   - **Security Hotspots:** Click the **Security Hotspots** tab to review `API_KEY = "CVWJ_DEMO_SECRET"`.
   - **Coverage:** View the line coverage percentage imported directly from JaCoCo.
   - **Code Smells:** View maintainability findings (unclosed streams, logger recommendations).

---

## 9. Comprehensive Troubleshooting & Pitfalls Matrix

| Symptom / Error | Root Cause | Exact Solution |
|---|---|---|
| `GET http://sonarqube:9000/api/v2/analysis/jres failed with HTTP 401 Unauthorized` | Missing or invalid SonarQube token, or credential ID mismatch in Jenkins. | Verify the token in Jenkins credentials is typed as `sonarqube-token` and that the token is valid in SonarQube (`My Account` -> `Security`). |
| `No plugin found for prefix 'sonar' in the current project` | Maven does not know the plugin group for `sonar`. | In `pom.xml`, include `<plugin><groupId>org.sonarsource.scanner.maven</groupId><artifactId>sonar-maven-plugin</artifactId></plugin>`. |
| `Connection refused: localhost:8090` or `localhost:9000` | Pipeline used `localhost` instead of the Docker service name. | Update `SONAR_HOST_URL` to `http://sonarqube:9000` so Docker resolves the container DNS across `devsecops-net`. |
| `QUALITY GATE STATUS: FAILED` | Code does not satisfy Quality Gate rules (e.g. unreviewed hotspots or coverage threshold). | Review the failed conditions at `http://43.204.100.125:8090/dashboard?id=cwvj-devsecops-demo`. Resolve code issues or mark hotspots as reviewed. |
| Pipeline hangs indefinitely in Sonar stage | Quality Gate wait timeout or unreachable SonarQube compute engine. | Check `docker logs sonarqube` to confirm Compute Engine (`ce`) is healthy and processing tasks. |
| Test coverage is reported as 0.0% in SonarQube | JaCoCo execution did not run before SonarQube or XML report path was not passed. | Ensure `mvn clean test` runs with JaCoCo `report` goal bound to `test` phase, and pass `-Dsonar.coverage.jacoco.xmlReportPaths="target/site/jacoco/jacoco.xml"`. |

---

# Output

<img width="1917" height="845" alt="image" src="https://github.com/user-attachments/assets/c0aa644f-4272-434c-b4ce-1eb5cb116fd9" />
