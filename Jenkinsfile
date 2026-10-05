pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    environment {
        DEPLOY_HOST = 'staging-deploy'          // SSH alias, see step 5
        DEPLOY_PATH = '~/staging/backup-manager'
        REPOSITORY = 'git@github.com:Pedro-Appel/nextcloud-backup-manager.git'
    }

    stages {
        stage('Checkout dev') {
            steps {
                git branch: 'dev',
                    url: env.REPOSITORY,
                    credentialsId: 'git-checkout-key'
            }
        }

        stage('Validate') {
            steps {
                sh './gradlew test integrationTest'
            }
        }

        stage('Await approval to merge into staging') {
            options {
                timeout(time: 24, unit: 'HOURS')
            }
            steps {
                input message: "Validations passed for dev @ ${env.GIT_COMMIT?.take(7)}. Deploy the staging candidate and publish the merge?",
                      ok: 'Merge & Deploy'
                      // submitter: 'youruser'   // uncomment + set to restrict who can approve
            }
        }

        stage('Prepare staging candidate') {
            steps {
                sshagent(credentials: ['git-checkout-key']) {
                    sh """
                        git config user.email "jenkins@mecha-rat"
                        git config user.name  "Jenkins"
                        git fetch origin +refs/heads/staging:refs/remotes/origin/staging +refs/heads/dev:refs/remotes/origin/dev
                        git checkout -B staging origin/staging
                        git merge --no-ff ${env.GIT_COMMIT} -m "Merge dev into staging (build #${env.BUILD_NUMBER})"
                    """
                }
            }
        }

        stage('Validate staging candidate') {
            steps {
                sh './gradlew test integrationTest'
            }
        }

        stage('Prepare jar') {
            steps {
                sh './gradlew clean shadowJar'
                sh 'mv ./build/libs/nextcloud-backup-manager-*-all.jar build/libs/nextcloud-backup-manager.jar'
            }
        }


        stage('Prepare environment file') {
            steps {
                echo 'Binding backup environment credential'
                withCredentials([file(credentialsId: 'backup-env-file', variable: 'ENVFILE')]) {
                    echo 'Credential bound; copying environment file'
                    sh '''
                        set -eux
                        test -f "$ENVFILE"
                        cp "$ENVFILE" backup.conf
                        chmod 600 backup.conf
                        test -s backup.conf
                    '''
                }
            }
        }

        stage('Sync code to staging host') {
            steps {
                sshagent(credentials: ['staging-ssh-key']) {
                    sh """
                        ssh ${DEPLOY_HOST} "mkdir -p ${DEPLOY_PATH}"
                        scp build/libs/nextcloud-backup-manager.jar ${DEPLOY_HOST}:${DEPLOY_PATH}/
                        scp backup.conf ${DEPLOY_HOST}:${DEPLOY_PATH}/
                        scp deploy/nextcloud-backup.service ${DEPLOY_HOST}:${DEPLOY_PATH}/
                        scp deploy/nextcloud-backup.timer ${DEPLOY_HOST}:${DEPLOY_PATH}/
                    """
                }
            }
        }

        stage('Deploy') {
            steps {
                sshagent(credentials: ['staging-ssh-key']) {
                    sh """
                        ssh ${DEPLOY_HOST} '\
                            cd ${DEPLOY_PATH} && \
                            chmod 0644 nextcloud-backup-manager.jar /opt/backup/nextcloud-backup-manager.jar  && \
                            chmod 0600 backup.conf /opt/backup/config/backup.conf'
                    """
                }
            }
        }

        stage('Publish staging merge') {
            steps {
                sshagent(credentials: ['git-checkout-key']) {
                    sh 'git push origin staging'
                }
            }
        }
    }

    post {
        success {
            echo "Staging candidate validated, deployed, and merged (#${env.BUILD_NUMBER}); timer was not changed."
        }
        aborted {
            echo "Merge/deploy was aborted, or the approval window timed out."
        }
        failure {
            echo "Pipeline FAILED — check which stage: validation, merge, build, or deploy."
        }
        cleanup {
            // Runs last, after whichever of the above fired, regardless of
            // outcome — removes the checkout and temporary deployment config
            // from the Jenkins workspace after each run.
            deleteDir()
        }
    }
}
