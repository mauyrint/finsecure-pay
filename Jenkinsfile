def isDeploy() { return (params.ACTION ?: 'deploy') == 'deploy' }

pipeline {
    agent any

    options {
        timestamps()
        ansiColor('xterm')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '25'))
        timeout(time: 90, unit: 'MINUTES')
    }

    parameters {
        choice(name: 'ACTION', choices: ['deploy', 'rollback'],
               description: 'deploy = full CI/CD run. rollback = skip build and roll EKS back.')
        string(name: 'ROLLBACK_TAG', defaultValue: '',
               description: 'rollback only: image tag to restore (blank = previous ReplicaSet revision)')
    }

    environment {
        APP_NAME        = 'finsecure-pay'
        K8S_NAMESPACE   = 'finsecure'
        TARGET_PLATFORM = 'linux/amd64'
        MVN             = "mvn -B -ntp -Dmaven.repo.local=${JENKINS_HOME}/.m2/repository"
        TRIVY_CACHE_DIR = "${JENKINS_HOME}/.cache/trivy"
        KUBECONFIG      = "${WORKSPACE}/.kube/config"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_SHORT   = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
                    env.POM_VERSION = sh(script: "${MVN} -q help:evaluate -Dexpression=project.version -DforceStdout",
                                         returnStdout: true).trim()
                    env.IMAGE_TAG   = "${env.POM_VERSION}-${env.BUILD_NUMBER}-${env.GIT_SHORT}"
                    env.IMAGE       = "${env.DOCKERHUB_REPO}:${env.IMAGE_TAG}"
                    currentBuild.displayName = isDeploy() ? "#${env.BUILD_NUMBER} ${env.IMAGE_TAG}"
                                                          : "#${env.BUILD_NUMBER} ROLLBACK ${params.ROLLBACK_TAG ?: 'previous'}"
                }
                sh 'mkdir -p reports && git log -1 --pretty="Commit %h by %an: %s"'
            }
        }

        stage('Build') {
            when { expression { isDeploy() } }
            steps {
                sh '$MVN clean compile'
            }
        }

        stage('Unit Test') {
            when { expression { isDeploy() } }
            steps {
                sh '$MVN test'
            }
            post {
                always {
                    junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: false
                    recordCoverage(tools: [[parser: 'JACOCO', pattern: 'target/site/jacoco/jacoco.xml']],
                                   id: 'jacoco', name: 'JaCoCo Coverage')
                }
            }
        }

        stage('Package') {
            when { expression { isDeploy() } }
            steps {

                sh '$MVN -DskipTests verify'
                archiveArtifacts artifacts: 'target/finsecure-pay.jar', fingerprint: true
            }
        }

        stage('Security Checks') {
            when { expression { isDeploy() } }
            parallel {
                stage('SAST: SonarQube') {
                    steps {
                        withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {

                            sh '''$MVN -DskipTests sonar:sonar \
                                    -Dsonar.host.url="$SONAR_HOST_URL" \
                                    -Dsonar.projectVersion="$IMAGE_TAG" \
                                    -Dsonar.qualitygate.wait=true \
                                    -Dsonar.qualitygate.timeout=300'''
                        }
                    }
                }
                stage('SCA: Dependencies') {
                    steps {
                        withCredentials([string(credentialsId: 'nvd-api-key', variable: 'NVD_API_KEY')]) {
                            script {
                                if (env.NVD_API_KEY?.trim()) {

                                    sh '$MVN -DskipTests dependency-check:check'
                                } else {

                                    echo 'NVD_API_KEY not set - using Trivy for dependency scanning'
                                    sh '''trivy rootfs --cache-dir "$TRIVY_CACHE_DIR" --cache-backend memory --scanners vuln --format table \
                                            --output reports/trivy-sca.txt target/
                                          cat reports/trivy-sca.txt
                                          trivy rootfs --cache-dir "$TRIVY_CACHE_DIR" --cache-backend memory --skip-db-update --skip-java-db-update --scanners vuln \
                                            --severity HIGH,CRITICAL --exit-code 1 target/'''
                                }
                            }
                        }
                    }
                    post {
                        always {
                            script {
                                if (fileExists('target/dependency-check-report.xml')) {
                                    dependencyCheckPublisher pattern: 'target/dependency-check-report.xml'
                                }
                            }
                            archiveArtifacts artifacts: 'target/dependency-check-report.*, reports/trivy-sca.txt',
                                             allowEmptyArchive: true
                        }
                    }
                }
                stage('Secrets: Gitleaks') {
                    steps {
                        sh 'gitleaks git --redact --exit-code 1 --report-format sarif --report-path reports/gitleaks.sarif .'
                    }
                }
                stage('IaC: Trivy config') {
                    steps {
                        sh '''trivy config --cache-dir "$TRIVY_CACHE_DIR" --severity HIGH,CRITICAL --exit-code 1 \
                                --output reports/trivy-config.txt k8s/ \
                              && trivy config --cache-dir "$TRIVY_CACHE_DIR" --severity HIGH,CRITICAL --exit-code 1 \
                                --output reports/trivy-dockerfile.txt Dockerfile'''
                    }
                }
            }
        }

        stage('Container Build') {
            when { expression { isDeploy() } }
            steps {
                sh '''docker build --pull --platform "$TARGET_PLATFORM" \
                        --build-arg VERSION="$IMAGE_TAG" \
                        --build-arg REVISION="$GIT_COMMIT" \
                        --build-arg CREATED="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
                        -t "$IMAGE" .
                      docker image inspect "$IMAGE" --format 'Built {{.Id}} ({{.Architecture}}) size={{.Size}}' '''
            }
        }

        stage('Image Scan: Trivy') {
            when { expression { isDeploy() } }
            steps {

                sh '''trivy image --cache-dir "$TRIVY_CACHE_DIR" --scanners vuln --format json \
                        --output reports/trivy-image.json "$IMAGE"
                      trivy image --cache-dir "$TRIVY_CACHE_DIR" --skip-db-update --scanners vuln --format template \
                        --template "@/opt/trivy/contrib/html.tpl" --output reports/trivy-image.html "$IMAGE" || true
                      trivy image --cache-dir "$TRIVY_CACHE_DIR" --skip-db-update --scanners vuln \
                        --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 "$IMAGE"'''
            }
            post {
                always {
                    publishHTML(target: [reportDir: 'reports', reportFiles: 'trivy-image.html',
                                         reportName: 'Trivy Image Scan', allowMissing: true,
                                         keepAll: true, alwaysLinkToLastBuild: true])
                }
            }
        }

        stage('Push') {
            when { expression { isDeploy() } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-creds',
                                                  usernameVariable: 'DH_USER', passwordVariable: 'DH_TOKEN')]) {
                    sh '''echo "$DH_TOKEN" | docker login -u "$DH_USER" --password-stdin
                          docker push "$IMAGE"
                          docker image inspect "$IMAGE" --format '{{index .RepoDigests 0}}' | tee reports/image-digest.txt'''
                }
            }
            post { always { sh 'docker logout || true' } }
        }

        stage('Deploy to EKS') {
            when { expression { isDeploy() } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'aws-deployer',
                                                  usernameVariable: 'AWS_ACCESS_KEY_ID',
                                                  passwordVariable: 'AWS_SECRET_ACCESS_KEY'),
                                 string(credentialsId: 'finsecure-api-key', variable: 'FINSECURE_API_KEY')]) {
                    sh 'APP_VERSION="$IMAGE_TAG" CHANGE_CAUSE="Jenkins #$BUILD_NUMBER deploy $IMAGE_TAG" scripts/deploy.sh'
                }
            }
        }

        stage('Smoke Test') {
            when { expression { isDeploy() } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'aws-deployer',
                                                  usernameVariable: 'AWS_ACCESS_KEY_ID',
                                                  passwordVariable: 'AWS_SECRET_ACCESS_KEY'),
                                 string(credentialsId: 'finsecure-api-key', variable: 'FINSECURE_API_KEY')]) {
                    script {
                        try {
                            sh 'scripts/smoke-test.sh "$IMAGE_TAG"'
                        } catch (err) {
                            echo 'Smoke test failed - automatically rolling back to the previous revision'
                            sh 'scripts/rollback.sh'
                            throw err
                        }
                    }
                }
            }
        }

        stage('Rollback') {
            when { expression { !isDeploy() } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'aws-deployer',
                                                  usernameVariable: 'AWS_ACCESS_KEY_ID',
                                                  passwordVariable: 'AWS_SECRET_ACCESS_KEY'),
                                 string(credentialsId: 'finsecure-api-key', variable: 'FINSECURE_API_KEY')]) {
                    sh 'scripts/rollback.sh "$ROLLBACK_TAG"'
                    sh 'scripts/smoke-test.sh "${ROLLBACK_TAG:-}"'
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'reports/**', allowEmptyArchive: true
        }
        success {
            emailext(
                to: "${env.NOTIFY_EMAIL}",
                subject: "[SUCCESS] ${env.JOB_NAME} #${env.BUILD_NUMBER} - ${params.ACTION ?: 'deploy'} ${env.IMAGE_TAG}",
                mimeType: 'text/html',
                body: """<h3>FinSecure Pay pipeline succeeded</h3>
                         <p><b>Action:</b> ${params.ACTION ?: 'deploy'}<br/>
                         <b>Image:</b> ${env.IMAGE}<br/>
                         <b>Commit:</b> ${env.GIT_SHORT}<br/>
                         <b>Duration:</b> ${currentBuild.durationString}</p>
                         <p><a href="${env.BUILD_URL}">Open build</a></p>""")
        }
        failure {
            emailext(
                to: "${env.NOTIFY_EMAIL}",
                subject: "[FAILED] ${env.JOB_NAME} #${env.BUILD_NUMBER} - deployment blocked",
                mimeType: 'text/html',
                attachLog: true,
                compressLog: true,
                body: """<h3 style="color:#b00020">FinSecure Pay pipeline FAILED - nothing was deployed</h3>
                         <p><b>Commit:</b> ${env.GIT_SHORT}<br/>
                         <b>Image tag:</b> ${env.IMAGE_TAG}</p>
                         <p>Check the failed stage (tests, quality gate or security gate) at
                         <a href="${env.BUILD_URL}">${env.BUILD_URL}</a>. The console log is attached.</p>""")
        }
        cleanup {
            sh 'if [ -n "${IMAGE:-}" ]; then docker image rm "$IMAGE" >/dev/null 2>&1 || true; fi; rm -rf .kube'
        }
    }
}
