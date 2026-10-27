FROM jenkins/jenkins:lts-jdk21

USER root
ARG TRIVY_VERSION=0.69.3
ARG GITLEAKS_VERSION=8.30.1
ARG MAVEN_VERSION=3.9.11

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg unzip jq gettext-base git \
 && install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
      > /etc/apt/sources.list.d/docker.list \
 && apt-get update && apt-get install -y --no-install-recommends docker-ce-cli docker-buildx-plugin \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      | tar -xz -C /opt \
 && ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn

RUN set -eux; ARCH="$(dpkg --print-architecture)"; \
    KVER="$(curl -fsSL https://dl.k8s.io/release/stable.txt)"; \
    curl -fsSLo /usr/local/bin/kubectl "https://dl.k8s.io/release/${KVER}/bin/linux/${ARCH}/kubectl"; \
    chmod +x /usr/local/bin/kubectl; \
    curl -fsSLo /tmp/awscli.zip "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip"; \
    unzip -q /tmp/awscli.zip -d /tmp && /tmp/aws/install && rm -rf /tmp/aws /tmp/awscli.zip

RUN set -eux; ARCH="$(dpkg --print-architecture)"; \
    case "$ARCH" in amd64) T=Linux-64bit; G=x64;; arm64) T=Linux-ARM64; G=arm64;; *) exit 1;; esac; \
    cd /tmp; \
    curl -fsSLO "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_${T}.tar.gz"; \
    curl -fsSLO "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_checksums.txt"; \
    grep " trivy_${TRIVY_VERSION}_${T}.tar.gz$" "trivy_${TRIVY_VERSION}_checksums.txt" | sha256sum -c -; \
    mkdir -p /opt/trivy && tar -xzf "trivy_${TRIVY_VERSION}_${T}.tar.gz" -C /opt/trivy; \
    ln -s /opt/trivy/trivy /usr/local/bin/trivy; \
    curl -fsSLO "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_${G}.tar.gz"; \
    tar -xzf "gitleaks_${GITLEAKS_VERSION}_linux_${G}.tar.gz" -C /usr/local/bin gitleaks; \
    rm -f /tmp/*.tar.gz /tmp/*.txt; \
    trivy --version && gitleaks version && kubectl version --client && aws --version && mvn -v

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false" \
    CASC_JENKINS_CONFIG=/var/jenkins_home/casc_configs

USER jenkins
