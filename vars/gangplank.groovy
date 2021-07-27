// Run gangplank

// Available parameters:
//     artifacts:    list    -- list of artifacts to be built
//     extraFlags:   string  -- Extra flags to use
//     image:        string  -- Name of the image to be used
//     mode:         string  -- Gangplank Mode
//     workDir:      string  -- Cosa working directory

def buildArtifact(params = [:]) {
    gangplankCmd = _getMode(params)
    gangplankCmd = _getArtifacts(params, gangplankCmd)
    runGangplank(gangplankCmd)
}

// Available parameters:
//     artifacts:     list    -- List of artifacts to be built
//     extraFlags:    string  -- Extra flags to use
//     image:         string  -- Name of the image to be used
//     mode:          string  -- Gangplank Mode
//     minioServeDir: string  -- Location to service minio from
//     workDir:       string  -- Cosa working directory

def buildParallelArtifacts(params = [:]) {
    gangplankCmd = _getMode(params)
    if (params['artifacts']) {
        def artifacts = params['artifacts']
        def configFile = startMinio(params)

        // Call one pod for each artifact in parallel
        parallel artifacts.inject([:]) { d, i -> d[i] = {
            startParallel(configFile, "${gangplankCmd} -A ${i} ")
        }; d }
        _finalize()
    }
}

// Available parameters:
//     artifacts:    list    -- List of artifacts to be built
//     extraFlags:   string  -- Extra flags to use
//     singlePod:    boolean -- Run generateSinglePod

def generateSpec(params = [:]) {
    if(params['singlePod']) {
        gangplankCmd = "gangplank generateSinglePod  "
    } else {
        gangplankCmd = "gangplank generate "
    }
    gangplankCmd = _getArtifacts(params, gangplankCmd)
    gangplankCmd = _getFlags(params, gangplankCmd)
    fileName = "/tmp/jobspec-${UUID.randomUUID()}.spec"
    gangplankCmd += "--yaml-out ${fileName} "
    runGangplank(gangplankCmd)
    return fileName
}

// Available parameters:
//     configFile:   string  -- Name of the Minio config file previously created
//     gangplankCmd: string  -- Gangplank command to run
def startParallel(configFile, gangplankCmd) {
    gangplankCmd += "-m ${configFile}"
    runGangplank(gangplankCmd)
}

// Available parameters:
//     artifacts:    list    -- List of artifacts to be built
//     extraFlags:   string  -- Extra flags to use
//     image:        string  -- Name of the image to be used
//     mode:         string  -- Gangplank Mode
//     minioServeDir string  -- Location to service minio from
//     workDir:      string  -- Cosa working directory
def startMinio(params =[:]) {
    def configFile = "/tmp/${UUID.randomUUID()}-minio.yaml"
    def minioServeDir = params.get('minioServeDir', "/srv")
    def gangplankCmd = "gangplank minio -m ${configFile} -d ${minioServeDir}"

    // Minio needs to run in background, JENKINS_NODE_COOKIE allows it
    gangplankCmd = "export JENKINS_NODE_COOKIE=dontKillMe && nohup ${gangplankCmd} &"
    runGangplank(gangplankCmd)
    // Workaround - Wait for minio to start
    shwrap("sleep 0.5")

    return configFile
}

def runGangplank(gangplankCmd) {
    try {
        shwrap("${gangplankCmd}")
    } catch (Exception e) {
        print(e)
        currentBuild.result = 'ABORTED'
        error("Error running gangplank.")
    }
}

// Available parameters:
//     artifacts:     list    -- List of artifacts to be built
//     extraFlags:    string  -- Extra flags to use
//     mode:          string  -- Gangplank Mode
//     spec:          string  -- Name of the spec file
def runSpec(params =[:]) {
    if (params['spec']) {
        gangplankCmd = _getMode(params)
        gangplankCmd += "--spec ${params['spec']} "
        runGangplank(gangplankCmd)
    } else {
        error("runSpec requires a spec param.")
    }
}

// Available parameters:
//     cmd:           string  -- the single command to run
//     mode:          string  -- Gangplank Mode
//     extraFlags:    string  -- Extra flags to use
def runSingleCmd(params = [:]) {
    if (params['cmd']) {
        gangplankCmd = _getMode(params)
        gangplankCmd += " --singleCmd \"${params['cmd']}\""
        runGangplank(gangplankCmd)
    } else {
        error("runSingleCmd requires a cmd param.")
    }
}

// A set of default parameters for building our FCOS AARCH64 builds via gangplank.
// - use a locally build COSA because we don't currently build/push multi-arch COSA anywhere. 
// - use pod mode with --podman for remote podman execution
// - use the 'builds' "bucket" will fetch artifacts back into the expected 'builds' directory
// - target aarch64 architecture
def getGangPlankFCOSAARCH64Params() {
    return [mode: 'pod',
            extraFlags: '--podman --bucket=builds --arch=aarch64',
            image: 'localhost/coreos-assembler:latest']
}

// A function to wrap what's needed to run gangplank for our aarch64
// builder host. Accepts a params map with the usual parameters for
// a call do runSpec or runSingleCmd.
def gangPlankFCOSAARCH64BuilderWrapper(params = [:]) {
    withCredentials([
        string(credentialsId: 'fcos-aarch64-builder-host-string',
               variable: 'REMOTEHOST'),
        string(credentialsId: 'fcos-aarch64-builder-uid-string',
               variable: 'REMOTEUID'),
        sshUserPrivateKey(credentialsId: 'fcos-aarch64-builder-sshkey-key',
                          usernameVariable: 'REMOTEUSER',
                          keyFileVariable: 'CONTAINER_SSHKEY')
    ]) {
        withEnv(["CONTAINER_HOST=ssh://${REMOTEUSER}@${REMOTEHOST}/run/user/${REMOTEUID}/podman/podman.sock"]) {
            shwrap("""
            # workaround bug: https://github.com/jenkinsci/configuration-as-code-plugin/issues/1646
            sed -i s/^----BEGIN/-----BEGIN/ \$CONTAINER_SSHKEY
            """)
            if (params['spec']) {
                runSpec(params)
            } else {
                runSingleCmd(params)
            }
        }
    }
}

def _finalize() {
    runGangplank("gangplank pod -A finalize")
}

def _getArtifacts(params =[:], gangplankCmd) {
   if (params['artifacts']) {
        def artifacts = params['artifacts'].join(",")
        gangplankCmd += "--build-artifact ${artifacts} "
        return gangplankCmd
    }
    return gangplankCmd
}

def _getFlags(params = [:], gangplankCmd) {
    if (params['extraFlags'])  {
        gangplankCmd += params['extraFlags'] + " "
        return gangplankCmd
    }
    return gangplankCmd
}

def _getImage(params = [:], gangplankCmd) {
    if (params['image']) {
        gangplankCmd += "--image ${params['image']} "
        return gangplankCmd
    }
    return gangplankCmd
}

def _getMode(params = [:]) {
    gangplankCmd = "gangplank " + params.get('mode', "pod") + " "
    gangplankCmd = _getFlags(params, gangplankCmd)
    gangplankCmd = _getImage(params, gangplankCmd)
    gangplankCmd = _getWorkDir(params, gangplankCmd)
    return gangplankCmd
}

def _getWorkDir(params = [:], gangplankCmd) {
    if(params['cosaDir']) {
        gangplankCmd += "--workDir ${utils.getCosaDir(params)} "
        return gangplankCmd
    }
    return gangplankCmd
}
