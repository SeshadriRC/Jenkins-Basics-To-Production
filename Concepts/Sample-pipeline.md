# Jenkins Sample Pipeline for DevOps Interview

## 1. Pipeline Overview

This pipeline automates the CI/CD process for an application.

```text
Developer pushes code to Git
          ↓
Jenkins Pipeline starts
          ↓
Checkout source code
          ↓
Build application using Maven
          ↓
Run unit tests
          ↓
Build Docker image
          ↓
Push image to Docker Hub
          ↓
Deploy application
```

## 2. Sample Jenkinsfile

# Jenkins Declarative Pipeline — With Comments

```groovy
pipeline {
    // Run the pipeline on any available Jenkins agent
    agent any

    // Define environment variables accessible throughout the pipeline
    environment {
        APP_NAME = 'employee-app'
        DOCKER_IMAGE = 'your-dockerhub-username/employee-app'
        DOCKER_CREDENTIALS = 'dockerhub-creds'
    }

    // Configure pipeline execution options
    options {
        timestamps() // Add timestamps to console log output
        timeout(time: 20, unit: 'MINUTES') // Stop the pipeline if it runs longer than 20 minutes
        buildDiscarder(logRotator(numToKeepStr: '10')) // Retain only the 10 most recent builds
    }

    // Define the stages of the CI/CD pipeline
    stages {

        // Stage 1: Download source code from GitHub
        stage('Checkout') {
            steps {
                // Check out the main branch from the Git repository
                git branch: 'main',
                    url: 'https://github.com/your-username/employee-app.git'
            }
        }

        // Stage 2: Compile the application and package it
        stage('Build') {
            steps {
                // Clean previous build files and package the application
                // Skip tests here because they run in the next stage
                sh 'mvn clean package -DskipTests'
            }
        }

        // Stage 3: Execute unit tests
        stage('Unit Tests') {
            steps {
                // Run the application's unit tests using Maven
                sh 'mvn test'
            }
        }

        // Stage 4: Build a Docker image
        stage('Build Docker Image') {
            steps {
                // Build the Docker image and tag it with the Jenkins build number
                // Example tag: your-dockerhub-username/employee-app:15
                sh 'docker build -t ${DOCKER_IMAGE}:${BUILD_NUMBER} .'
            }
        }

        // Stage 5: Push the Docker image to Docker Hub
        stage('Push Docker Image') {
            steps {
                // Retrieve Docker Hub credentials securely from Jenkins Credentials
                withCredentials([
                    usernamePassword(
                        credentialsId: "${DOCKER_CREDENTIALS}",
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )
                ]) {
                    // Log in securely, push the image, and log out
                    sh '''
                        # Authenticate to Docker Hub without exposing the password in the command arguments
                        echo "$DOCKER_PASS" | docker login \
                            -u "$DOCKER_USER" --password-stdin

                        # Push the image tagged with the current Jenkins build number
                        docker push ${DOCKER_IMAGE}:${BUILD_NUMBER}

                        # Log out from Docker Hub
                        docker logout
                    '''
                }
            }
        }

        // Stage 6: Deploy the application
        stage('Deploy') {
            steps {
                // Display a deployment message
                echo 'Deploying application to the target environment'

                // Add actual deployment commands here
                // For example, update a Kubernetes Deployment with the new image
            }
        }
    }

    // Define actions to execute after the pipeline finishes
    post {

        // Execute this block if all stages succeed
        success {
            echo 'Pipeline completed successfully!'
        }

        // Execute this block if the pipeline fails
        failure {
            echo 'Pipeline failed. Check the console output.'
        }

        // Execute this block regardless of success or failure
        always {
            echo 'Pipeline execution completed.'
        }
    }
}
```

**Dockerfile for above project**

```bash
# Stage 1: Build the Java application
FROM maven:3.9-eclipse-temurin-17 AS build

# Set the working directory inside the container
WORKDIR /app

# Copy Maven configuration file
COPY pom.xml .

# Download project dependencies
RUN mvn dependency:go-offline

# Copy application source code
COPY src ./src

# Build the application and generate the JAR file
RUN mvn clean package -DskipTests


# Stage 2: Run the Java application
FROM eclipse-temurin:17-jre

# Set the working directory
WORKDIR /app

# Copy the generated JAR from the build stage
COPY --from=build /app/target/*.jar app.jar

# Expose application port
EXPOSE 8080

# Start the Java application
CMD ["java", "-jar", "app.jar"]
```

## Pipeline Flow

```text
Checkout from GitHub
        ↓
Build with Maven
        ↓
Run Unit Tests
        ↓
Build Docker Image
        ↓
Push Image to Docker Hub
        ↓
Deploy Application
        ↓
Success / Failure Messages
```

## Important Notes

- Replace the GitHub URL and Docker Hub image name with your actual values.
- Create a Jenkins username/password credential with ID `dockerhub-creds`.
- Ensure the Jenkins agent has Maven, Docker, and the required permissions installed.
- The `Deploy` stage is a placeholder; add actual deployment commands for your environment.
- For production, use a tested deployment process with health checks and rollback capabilities.
- `APP_NAME` is defined but not currently used in the pipeline.

**Note:** Replace the Git repository URL and Docker Hub username with your actual values. Configure the `dockerhub-creds` credential in Jenkins. The Jenkins agent must have Maven, Docker, and the required permissions installed. The deployment stage is a placeholder; add the commands appropriate for your target environment.

The example separates packaging from testing for demonstration. In a real pipeline, prefer running tests as part of the build before publishing the artifact or image.

## 3. Important Jenkins Pipeline Concepts

| Keyword | Purpose |
|---|---|
| `pipeline` | Defines a Declarative Pipeline |
| `agent any` | Runs on any available eligible agent |
| `environment` | Defines environment variables |
| `stages` | Groups the pipeline stages |
| `stage` | Defines an individual stage |
| `steps` | Contains the commands or actions |
| `sh` | Executes shell commands on Unix/Linux agents |
| `withCredentials` | Makes stored credentials available securely within a block |
| `post` | Executes actions after pipeline completion |
| `timeout` | Stops a pipeline that exceeds the configured duration |
| `buildDiscarder` | Retains a limited number of builds |

