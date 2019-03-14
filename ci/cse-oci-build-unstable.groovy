pipeline {
  agent {
    label 'build-zenoss-appliance'
  }
  parameters {
    booleanParam(name: "REFRESH", defaultValue: false, description: "Check this to reload the job with your branch's parameters. Everything after BUILD_BRANCH is ignored.")
    string(name: "BUILD_BRANCH", defaultValue: "develop", description: "The git branch for cse-image build logic code. (github.com/zenoss/cse-image-build)")
    choice(name: "IMAGE_MATURITY", choices: "unstable", description: "Image maturity.")
    choice(name: "CSE_MATURITY", choices: "unstable\nstable", description: "CSE maturity")
    string(name: "CSE_VERSION", defaultValue: "7.0.0", description: "Version of Zenoss Resource Manager installed in the image.")
    string(name: "CSE_BUILD_NUMBER", defaultValue: "", description: "Build of Resource Manager to pull artifacts from.\n\nUse 'Product Build Number' from <a href=\"http://platform-jenkins.zenoss.eng/job/product-assembly/job/develop/job/begin/\">product-assembly build</a>")
    choice(name: "CC_MATURITY", choices: "unstable\nstable", description: "CC maturity")
    string(name: "CC_VERSION", defaultValue: "1.6.0", description: "Version of Control Center installed on the image.")
    string(name: "CC_BUILD_NUMBER", defaultValue: "", description: "Build of Control Center / serviced to pull CC artifacts from.\n\nUse build number from <a href=\"http://platform-jenkins.zenoss.eng/job/ControlCenter/job/develop/job/merge-start/\">Control Center Build</a>")
    choice(name: "SOURCE_AMI", description: "Source AMI.", choices: "\nami-0735ea082a1534cac")
    credentials(name: "S3_ACCESS_KEY", required: true, credentialType: "Secret text", description: "Access key for uploading to S3 bucket.")
    credentials(name: "S3_SECRET_KEY", required: true, credentialType: "Secret text", description: "Secret key for uploading to the S3 bucket.")
    choice(name: "SECURITY_GROUP", description: "Security group ID for launching the AMI.", choices: "\nsg-0695e3c387b9d430c")
    choice(name: "VPC", description: "VPC for launching the AMI.", choices: "\nvpc-aaaeadc3")
    choice(name: "SUBNET", description: "Subnet ID for launching the AMI.", choices: "\nsubnet-a3aeadca")
  }
  stages {
    stage('Refresh') {
      when { expression { return params.REFRESH }}
      steps {
        script {
          currentBuild.displayName = "Refresh for ${BUILD_BRANCH}"
          currentBuild.result = 'SUCCESS'
        }
      }
    }
    stage('Build AMI') {
      when { expression { return ! params.REFRESH }}
      stages {
        stage('Download artifacts') {
          environment {
            GCP_CREDS = credentials('cse-compute-image-push')
          }
          agent {
            dockerfile {
              label 'build-zenoss-appliance'
              filename 'Dockerfile'
              dir 'ci'
              args '-v ${PWD}/packer-cse:/build -u root'
              reuseNode true
            }
          }
          steps {
            sh 'rm -rf staging-*'
            sh './ci/get_files.sh'
          }
        }
        stage('Build image') {
          environment {
            PATH = "${PATH}:${PWD}/packer-cse"
          }
          steps {
            script {
              currentBuild.displayName = "${IMAGE_MATURITY} AMI build ${BUILD_NUMBER} CSE-${CSE_VERSION}-${CSE_BUILD_NUMBER}-${CSE_MATURITY} CC-${CC_VERSION}-${CC_BUILD_NUMBER}-${CC_MATURITY}"
            }
            wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
                sh 'echo "starting image build"'
                sh 'packer inspect ./packer-cse/ami_template.json'
                sh 'rm -rf ami-build'
                withCredentials([string(credentialsId: "${S3_ACCESS_KEY}", variable: "S3_ACCESS_KEY_CREDS"),
                                 string(credentialsId: "${S3_SECRET_KEY}", variable: "S3_SECRET_KEY_CREDS")]){
                  sh '''packer build \
                      -var "image_init_script=./packer-cse/ami_init.sh" \
                      -var "artifact_source_dir=${PWD}/staging" \
                      -var enable_root=1 \
                      ./packer-cse/ami_template.json'''
                }
                sh "./ci/last_ami.sh packer-manifest.json us-east-1 > collector-ami.txt"
                archiveArtifacts artifacts: 'collector-ami.txt', allowEmptyArchive: true, onlyIfSuccessful: true
            }
          }
        }
      }
    }
  }
}
