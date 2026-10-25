// Builds main, pushes the server image and commits the new tag to a GitOps repository.
//
// Every deployment-specific value is a job parameter, configured on the Jenkins job:
//   IMAGE_REPOSITORY         image to push, without a tag (registry/owner/name)
//   REGISTRY_CREDENTIALS_ID  "username with password" credential that can push IMAGE_REPOSITORY
//   GITOPS_REPO_URL          HTTPS URL of the GitOps repository
//   GITOPS_BRANCH            branch of the GitOps repository to commit to
//   GITOPS_MANIFEST_PATH     manifest whose image lines for IMAGE_REPOSITORY get the new tag
//   GITOPS_CREDENTIALS_ID    "secret text" token with write access to the GitOps repository
//   GITOPS_COMMIT_NAME       author name of the GitOps commit
//   GITOPS_COMMIT_EMAIL      author email of the GitOps commit
//
// There is deliberately no parameters {} block: declaring one would replace the job's configured
// values with the file's on the next run.
pipeline {
    agent any

    options {
        timeout(time: 45, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
        skipDefaultCheckout()
        timestamps()
    }

    environment {
        MAVEN_IMAGE      = 'maven:3.9-eclipse-temurin-21'
        MAVEN_OPTS       = '-Xmx1g'
        DOCKER_CLI_IMAGE = 'docker:27-cli'
    }

    stages {
        stage('Validate parameters') {
            steps {
                script {
                    def required = ['IMAGE_REPOSITORY', 'REGISTRY_CREDENTIALS_ID',
                                    'GITOPS_REPO_URL', 'GITOPS_BRANCH', 'GITOPS_MANIFEST_PATH',
                                    'GITOPS_CREDENTIALS_ID', 'GITOPS_COMMIT_NAME', 'GITOPS_COMMIT_EMAIL']
                    def missing = required.findAll { !params[it]?.trim() }
                    if (missing) {
                        error "Missing job parameters: ${missing.join(', ')}. Configure them on the job."
                    }
                }
            }
        }

        stage('Checkout') {
            steps {
                script {
                    def scmVars = checkout scm
                    def branch = scmVars.GIT_BRANCH ?: ''
                    if (branch && !(branch in ['main', 'origin/main'])) {
                        error "This pipeline deploys main only; the job checked out ${branch}."
                    }
                    env.SHORT_SHA = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
                    env.IMAGE_TAG = "${env.BUILD_NUMBER}-${env.SHORT_SHA}"
                    // Stage containers run as the agent's uid without its supplementary groups, so
                    // they join the socket's group explicitly. Its id differs from host to host.
                    env.DOCKER_SOCKET_GID = sh(script: 'stat -c %g /var/run/docker.sock', returnStdout: true).trim()
                }
            }
        }

        stage('Verify') {
            agent {
                docker {
                    image "${MAVEN_IMAGE}"
                    args "--network host --group-add ${env.DOCKER_SOCKET_GID} -v /var/run/docker.sock:/var/run/docker.sock -v ${env.HOME}/.m2:/root/.m2"
                    reuseNode true
                }
            }
            environment {
                DOCKER_HOST                           = 'unix:///var/run/docker.sock'
                TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE = '/var/run/docker.sock'
                TESTCONTAINERS_RYUK_DISABLED          = 'true'
                // With host networking, published container ports are on localhost, not a gateway address.
                TESTCONTAINERS_HOST_OVERRIDE          = 'localhost'
            }
            steps {
                sh './mvnw -B --no-transfer-progress clean verify'
            }
            post {
                always {
                    junit testResults: '**/target/surefire-reports/*.xml, **/target/failsafe-reports/*.xml',
                          allowEmptyResults: true
                }
            }
        }

        stage('Build and push image') {
            // The Dockerfile needs BuildKit, so the image is built from a docker CLI image that ships
            // buildx, whatever the agent itself has installed. It drives the agent's Docker daemon.
            agent {
                docker {
                    image "${DOCKER_CLI_IMAGE}"
                    args "--group-add ${env.DOCKER_SOCKET_GID} -v /var/run/docker.sock:/var/run/docker.sock"
                    reuseNode true
                }
            }
            steps {
                // A per-build DOCKER_CONFIG keeps this login away from the agent's default
                // ~/.docker/config.json, which other pipelines on the same agent rely on.
                sh '''
                    set -eu
                    export DOCKER_CONFIG="$WORKSPACE/.docker"
                    mkdir -p "$DOCKER_CONFIG"
                    docker build -f jburrow-server/Dockerfile \
                      --build-arg GIT_COMMIT="$SHORT_SHA" \
                      -t "$IMAGE_REPOSITORY:$IMAGE_TAG" -t "$IMAGE_REPOSITORY:main" .
                '''
                withCredentials([usernamePassword(credentialsId: params.REGISTRY_CREDENTIALS_ID,
                                                  usernameVariable: 'REG_USER', passwordVariable: 'REG_TOKEN')]) {
                    sh '''
                        set -eu
                        export DOCKER_CONFIG="$WORKSPACE/.docker"
                        registry="${IMAGE_REPOSITORY%%/*}"
                        case "$registry" in
                            (*.*|*:*|localhost) ;;
                            (*) registry=docker.io ;;
                        esac
                        printf '%s' "$REG_TOKEN" | docker login "$registry" -u "$REG_USER" --password-stdin
                    '''
                    retry(3) {
                        sh 'DOCKER_CONFIG="$WORKSPACE/.docker" docker push "$IMAGE_REPOSITORY:$IMAGE_TAG"'
                    }
                    retry(3) {
                        sh 'DOCKER_CONFIG="$WORKSPACE/.docker" docker push "$IMAGE_REPOSITORY:main"'
                    }
                }
            }
        }

        stage('Update GitOps repository') {
            steps {
                withCredentials([string(credentialsId: params.GITOPS_CREDENTIALS_ID, variable: 'GITOPS_TOKEN')]) {
                    // The credential helper reads GITOPS_TOKEN when git asks for it, so the token
                    // never appears in a command line or in the clone's .git/config.
                    sh '''
                        set -eu
                        helper='!f() { echo username=x-access-token; echo "password=$GITOPS_TOKEN"; }; f'
                        rm -rf gitops
                        git -c credential.helper= -c credential.helper="$helper" \
                          clone --depth 1 --branch "$GITOPS_BRANCH" "$GITOPS_REPO_URL" gitops
                        cd gitops
                        git config credential.helper ''
                        git config --add credential.helper "$helper"
                        git config user.name "$GITOPS_COMMIT_NAME"
                        git config user.email "$GITOPS_COMMIT_EMAIL"

                        sed -i "s#image: ${IMAGE_REPOSITORY}:.*#image: ${IMAGE_REPOSITORY}:${IMAGE_TAG}#" "$GITOPS_MANIFEST_PATH"
                        if ! grep -q "image: ${IMAGE_REPOSITORY}:${IMAGE_TAG}" "$GITOPS_MANIFEST_PATH"; then
                            echo "No 'image: ${IMAGE_REPOSITORY}:' line in $GITOPS_MANIFEST_PATH; nothing was deployed." >&2
                            exit 1
                        fi
                        git add "$GITOPS_MANIFEST_PATH"
                        git commit -m "ci: deploy jburrow-server ${IMAGE_TAG}"

                        # The GitOps repository may be shared, so a push can race another pipeline's.
                        for attempt in 1 2 3; do
                            if git push origin "HEAD:$GITOPS_BRANCH"; then
                                exit 0
                            fi
                            [ "$attempt" -lt 3 ] || exit 1
                            git pull --rebase origin "$GITOPS_BRANCH"
                        done
                    '''
                }
            }
        }
    }

    post {
        always {
            sh '''
                if [ -n "${IMAGE_REPOSITORY:-}" ] && [ -n "${IMAGE_TAG:-}" ]; then
                    docker rmi "$IMAGE_REPOSITORY:$IMAGE_TAG" "$IMAGE_REPOSITORY:main" || true
                fi
                docker image prune -f || true
            '''
            // Also removes $WORKSPACE/.docker, and the registry login with it.
            cleanWs()
        }
    }
}
