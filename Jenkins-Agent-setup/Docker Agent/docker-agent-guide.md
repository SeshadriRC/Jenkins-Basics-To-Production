1. **Create the Agent Dockerfile:** Dockerfile.agent.
Create a file named `Dockerfile.agent` containing the build toolchain (Java 21, Maven, Docker CLI, Kubectl, Helm, Trivy):

```dockerfile
FROM jenkins/inbound-agent:latest

USER root

# Install system utilities, Java 21, and Maven
RUN apt-get update && apt-get install -y --no-install-recommends \
    apt-transport-https \
    ca-certificates \
    curl \
    gnupg \
    lsb-release \
    git \
    wget \
    maven \
    openjdk-21-jdk && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# 1. Install Docker CLI
RUN mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian $(lsb_release -cs) stable" > /etc/apt/sources.list.d/docker.list && \
    apt-get update && apt-get install -y --no-install-recommends docker-ce-cli && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# 2. Install Kubectl (Direct Binary)
RUN curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" && \
    install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl && \
    rm -f kubectl

# 3. Install Helm
RUN curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# 4. Install Trivy
RUN curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin

# 5. Configure Docker group access
RUN groupadd -f docker && usermod -aG docker jenkins

USER jenkins

```


2. **Build the Agent Image:**
Build the custom agent image in PowerShell:

```powershell
docker build -f Dockerfile.agent -t custom-jenkins-agent:latest .

```


3. **Register the Node in Jenkins UI:**
1. Navigate to **Manage Jenkins** $\rightarrow$ **Security**. Verify **TCP port for inbound agents** is enabled (set to **Fixed: 50000** or **Random**).
2. Go to **Manage Jenkins** $\rightarrow$ **Nodes** $\rightarrow$ **New Node**.
3. Enter Node Name: `docker-agent` $\rightarrow$ Select **Permanent Agent** $\rightarrow$ Click **Create**.
4. Fill in node parameters:
* **Remote root directory:** `/home/jenkins/agent`
* **Labels:** `docker-agent devsecops`
* **Usage:** *Use this node as much as possible*
* **Launch method:** *Launch agent by connecting it to the controller* (Inbound agent)


5. Click **Save**, open the created `docker-agent` node, and copy the alphanumeric string provided after `-secret`.


4. **Run the Inbound Agent Container:**
Start the agent container in PowerShell (replace `<AGENT_SECRET_KEY>` with your actual copied key):

```powershell
docker run -d --name jenkins-agent --restart unless-stopped `
  --network host `
  --group-add 0 `
  -v /var/run/docker.sock:/var/run/docker.sock `
  custom-jenkins-agent:latest `
  -url http://localhost:8080 `
  -secret <AGENT_SECRET_KEY> `
  -name docker-agent `
  -workDir /home/jenkins/agent

```


5. **Verify Node Connectivity & Tools:**
1. Check the Jenkins UI under **Nodes** to confirm `docker-agent` displays as **Connected** (in sync with the controller).
2. Run a health check inside the agent container from PowerShell:

```powershell
docker exec -it jenkins-agent docker ps
docker exec -it jenkins-agent kubectl version --client
docker exec -it jenkins-agent helm version
docker exec -it jenkins-agent trivy --version

```


---

### Pipeline Verification Job

To test the node within a pipeline, create a test declarative Pipeline job targeting the agent label:

```groovy
pipeline {
    agent {
        label 'docker-agent'
    }
    stages {
        stage('Tooling Verification') {
            steps {
                sh '''
                    java -version
                    mvn -version
                    docker --version
                    kubectl version --client
                    helm version
                    trivy --version
                '''
            }
        }
    }
}

```
